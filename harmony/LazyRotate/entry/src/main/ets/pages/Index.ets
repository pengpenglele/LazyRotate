import { common } from '@kit.AbilityKit';
import { pasteboard } from '@kit.BasicServicesKit';
import {
  RotationSource,
  RotationTarget,
  isKnownTargetCode,
  parseRotationTarget,
  rotationTargetHint,
  rotationTargetLabel
} from '../core/Contract';
import { AppPreferences } from '../core/AppPreferences';
import { RotationService } from '../core/RotationService';
import {
  DisplayInfo,
  currentRotationTarget,
  flippedCurrentRotationTarget,
  orientationLabel,
  readDisplayInfo,
  rotationLabel,
  subscribeDisplayChange,
  unsubscribeDisplayChange
} from '../core/DeviceState';
import {
  autoRotationLabel,
  currentApiVersion,
  openDisplaySettingsPage,
  readAutoRotationStatus
} from '../core/SystemRotationSettings';
import { LieDownEngine, LieDownSample } from '../core/LieDownEngine';
import {
  BOTTOM_BAR_HEIGHT,
  COLOR_AMBER,
  COLOR_AMBER_DEEP,
  COLOR_BG_0,
  COLOR_BG_1,
  COLOR_CARD,
  COLOR_CARD_ALT,
  COLOR_GREEN,
  COLOR_RED,
  COLOR_STROKE,
  COLOR_TEXT,
  COLOR_TEXT_DIM,
  PAGE_PADDING,
  RADIUS_BUTTON,
  RADIUS_CARD
} from '../ui/Theme';

const HDC_EXAMPLE: string =
  'hdc shell aa start -a EntryAbility -b com.lazyrotate.app -A com.lazyrotate.app.action.SET_ROTATION --ps target landscape';

@Entry
@Component
struct Index {
  @State tabIndex: number = 0;
  @StorageLink('pendingCommand') @Watch('onPendingCommandChanged') pendingCommand: string = '';

  @State rotation: number = 0;
  @State orientationText: string = '竖屏';
  @State autoRotationText: string = '未知';
  @State lastTargetText: string = '跟随系统';
  @State message: string = '';
  @State messageOk: boolean = true;

  @State lieAvailable: boolean = true;
  @State lying: boolean = false;
  @State lieConfidence: number = 0;
  @State lieInPlane: number = 0;

  @State floatEnabled: boolean = true;
  @State floatX: number = -1;
  @State floatY: number = -1;
  @State floatStartX: number = 0;
  @State floatStartY: number = 0;
  @State screenW: number = 360;
  @State screenH: number = 720;

  @State restoreOnLaunch: boolean = true;
  @State apiVersion: number = 0;
  @State persistenceReady: boolean = true;

  private service: RotationService | undefined = undefined;
  private prefs: AppPreferences | undefined = undefined;
  private context: common.UIAbilityContext | undefined = undefined;
  private engine: LieDownEngine = new LieDownEngine();
  private displayCallback: ((data: number) => void) | undefined = undefined;

  aboutToAppear(): void {
    const ctx = this.getUIContext().getHostContext() as common.UIAbilityContext;
    this.context = ctx;
    const service = new RotationService(ctx);
    this.service = service;
    this.prefs = service.getPreferences();
    this.apiVersion = currentApiVersion();
    this.persistenceReady = service.isPersistenceReady();
    this.restoreOnLaunch = service.getPreferences().getRestoreOnLaunch();
    this.floatEnabled = service.getPreferences().getFloatingBallEnabled();
    this.lastTargetText = rotationTargetLabel(service.getPreferences().getLastTarget());

    this.applyDisplayState();
    this.autoRotationText = autoRotationLabel(readAutoRotationStatus(ctx));
    this.restoreFloatPosition();
    this.displayCallback = subscribeDisplayChange((rotation: number) => {
      this.applyDisplayState();
      this.autoRotationText = autoRotationLabel(readAutoRotationStatus(ctx));
    });
    this.startLieDownEngine();

    // 外部指令优先：冷启动若带着指令，就不要再套用「上次方向」，否则两者互相覆盖
    if (this.pendingCommand.length > 0) {
      this.consumePendingCommand();
    } else {
      this.restoreLastTarget();
    }
  }

  aboutToDisappear(): void {
    const cb = this.displayCallback;
    if (cb !== undefined) {
      unsubscribeDisplayChange(cb);
      this.displayCallback = undefined;
    }
    this.engine.stop();
  }

  onPageShow(): void {
    const ctx = this.context;
    if (ctx !== undefined) {
      this.autoRotationText = autoRotationLabel(readAutoRotationStatus(ctx));
    }
    this.consumePendingCommand();
  }

  @Styles
  cardStyle() {
    .width('100%')
    .padding(16)
    .borderRadius(RADIUS_CARD)
    .backgroundColor(COLOR_CARD)
    .border({ width: 1, color: COLOR_STROKE })
  }

  build() {
    Stack({ alignContent: Alignment.TopStart }) {
      Column() {
        this.header()
        Scroll() {
          Column() {
            if (this.tabIndex === 0) {
              this.homeTab()
            } else if (this.tabIndex === 1) {
              this.guideTab()
            } else {
              this.settingsTab()
            }
          }
          .width('100%')
          .padding({ left: PAGE_PADDING, right: PAGE_PADDING, bottom: 24 })
          .alignItems(HorizontalAlign.Start)
        }
        .layoutWeight(1)
        .width('100%')
        .scrollBar(BarState.Off)
        .edgeEffect(EdgeEffect.Spring)

        if (this.message.length > 0) {
          Row() {
            Text(this.message)
              .fontSize(12)
              .fontColor(this.messageOk ? COLOR_GREEN : COLOR_RED)
              .layoutWeight(1)
              .maxLines(2)
            Text('×')
              .fontSize(16)
              .fontColor(COLOR_TEXT_DIM)
              .padding({ left: 10, right: 2 })
              .onClick(() => {
                this.message = '';
              })
          }
          .width('100%')
          .padding({ left: PAGE_PADDING, right: PAGE_PADDING, top: 8, bottom: 8 })
          .backgroundColor(COLOR_CARD)
        }

        this.bottomBar()
      }
      .width('100%')
      .height('100%')

      if (this.floatEnabled) {
        this.floatBall()
      }
    }
    .width('100%')
    .height('100%')
    .linearGradient({
      angle: 170,
      colors: [[COLOR_BG_0, 0.0], [COLOR_BG_1, 1.0]]
    })
  }

  @Builder
  header() {
    Row() {
      Column() {
        Text('屏幕方向控制')
          .fontSize(24)
          .fontWeight(FontWeight.Bold)
          .fontColor(COLOR_TEXT)
        Text('HarmonyOS 7 · 本应用窗口方向控制')
          .fontSize(12)
          .fontColor(COLOR_TEXT_DIM)
          .margin({ top: 4 })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)

      Text('仅本应用')
        .fontSize(11)
        .fontColor(COLOR_AMBER)
        .padding({ left: 10, right: 10, top: 5, bottom: 5 })
        .borderRadius(999)
        .border({ width: 1, color: COLOR_AMBER_DEEP })
        .backgroundColor('#1E1608')
    }
    .width('100%')
    .padding({ left: PAGE_PADDING, right: PAGE_PADDING, top: 18, bottom: 12 })
  }

  @Builder
  homeTab() {
    Column({ space: 12 }) {
      this.statusCard()
      this.targetGrid()
      this.primaryActions()
      this.lieDownCard()
      this.commandHint()
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  statusCard() {
    Column() {
      Row() {
        Text('当前显示')
          .fontSize(13)
          .fontColor(COLOR_TEXT_DIM)
        Blank()
        Text(this.orientationText)
          .fontSize(14)
          .fontColor(COLOR_TEXT)
          .fontWeight(FontWeight.Medium)
      }
      .width('100%')

      Text('屏幕旋转角（相对屏幕自然方向）：' + rotationLabel(this.rotation))
        .fontSize(10)
        .fontColor(COLOR_TEXT_DIM)
        .margin({ top: 4 })

      Divider()
        .color(COLOR_STROKE)
        .margin({ top: 12, bottom: 12 })

      Row() {
        Text('系统自动旋转')
          .fontSize(13)
          .fontColor(COLOR_TEXT_DIM)
        Blank()
        Text(this.autoRotationText)
          .fontSize(13)
          .fontColor(COLOR_TEXT)
      }
      .width('100%')
      .margin({ bottom: 8 })

      Row() {
        Text('本应用窗口策略')
          .fontSize(13)
          .fontColor(COLOR_TEXT_DIM)
        Blank()
        Text(this.lastTargetText)
          .fontSize(13)
          .fontColor(COLOR_AMBER)
      }
      .width('100%')

      Text('系统自动旋转状态为只读探测；写系统设置需要系统应用权限，本应用不申请。')
        .fontSize(10)
        .fontColor(COLOR_TEXT_DIM)
        .lineHeight(15)
        .margin({ top: 10 })
    }
    .cardStyle()
  }

  @Builder
  targetGrid() {
    Column({ space: 10 }) {
      Grid() {
        GridItem() {
          this.targetTile('竖屏', RotationTarget.PORTRAIT)
        }

        GridItem() {
          this.targetTile('横屏', RotationTarget.LANDSCAPE)
        }

        GridItem() {
          this.targetTile('反向竖屏', RotationTarget.PORTRAIT_INVERTED)
        }

        GridItem() {
          this.targetTile('反向横屏', RotationTarget.LANDSCAPE_INVERTED)
        }
      }
      .columnsTemplate('1fr 1fr')
      .rowsTemplate('1fr 1fr')
      .columnsGap(10)
      .rowsGap(10)
      .width('100%')
      .height(168)

      Text('以上策略只会改变本应用窗口；离开本应用后，其它应用仍按各自方向策略显示。')
        .fontSize(10)
        .fontColor(COLOR_TEXT_DIM)
        .lineHeight(15)
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  targetTile(label: string, target: RotationTarget) {
    Column() {
      Text(label)
        .fontSize(15)
        .fontWeight(FontWeight.Medium)
        .fontColor(COLOR_TEXT)
      Text(rotationTargetHint(target))
        .fontSize(9)
        .fontColor(COLOR_TEXT_DIM)
        .maxLines(2)
        .textAlign(TextAlign.Center)
        .margin({ top: 4 })
    }
    .width('100%')
    .height('100%')
    .justifyContent(FlexAlign.Center)
    .padding({ left: 8, right: 8 })
    .borderRadius(RADIUS_BUTTON)
    .backgroundColor(COLOR_CARD_ALT)
    .border({ width: 1, color: COLOR_STROKE })
    .onClick(() => {
      this.runApply(target);
    })
  }

  @Builder
  primaryActions() {
    Row({ space: 10 }) {
      Column() {
        Text('锁定当前')
          .fontSize(14)
          .fontWeight(FontWeight.Medium)
          .fontColor('#1A1206')
        Text('固定方向')
          .fontSize(9)
          .fontColor('#4A360F')
          .margin({ top: 2 })
      }
      .layoutWeight(1)
      .height(62)
      .justifyContent(FlexAlign.Center)
      .borderRadius(RADIUS_BUTTON)
      .backgroundColor(COLOR_AMBER)
      .onClick(() => {
        this.doLockCurrent();
      })

      Column() {
        Text('翻转 180°')
          .fontSize(14)
          .fontWeight(FontWeight.Medium)
          .fontColor(COLOR_TEXT)
        Text('侧躺换边')
          .fontSize(9)
          .fontColor(COLOR_TEXT_DIM)
          .margin({ top: 2 })
      }
      .layoutWeight(1)
      .height(62)
      .justifyContent(FlexAlign.Center)
      .borderRadius(RADIUS_BUTTON)
      .backgroundColor(COLOR_CARD_ALT)
      .border({ width: 1, color: COLOR_STROKE })
      .onClick(() => {
        this.doFlip();
      })

      Column() {
        Text('跟随系统')
          .fontSize(14)
          .fontWeight(FontWeight.Medium)
          .fontColor(COLOR_TEXT)
        Text('交还控制')
          .fontSize(9)
          .fontColor(COLOR_TEXT_DIM)
          .margin({ top: 2 })
      }
      .layoutWeight(1)
      .height(62)
      .justifyContent(FlexAlign.Center)
      .borderRadius(RADIUS_BUTTON)
      .backgroundColor(COLOR_CARD_ALT)
      .border({ width: 1, color: COLOR_STROKE })
      .onClick(() => {
        this.doFollow();
      })
    }
    .width('100%')
  }

  @Builder
  lieDownCard() {
    Column() {
      Row() {
        Text('躺姿检测')
          .fontSize(13)
          .fontColor(COLOR_TEXT_DIM)
        Blank()
        Text(this.lieAvailable ? '重力传感器' : '传感器不可用')
          .fontSize(10)
          .fontColor(COLOR_TEXT_DIM)
      }
      .width('100%')

      Text(this.lying
        ? '屏幕接近水平：系统自动旋转开始「猜」方向，建议锁定'
        : '当前握持较稳，系统自动旋转可用')
        .fontSize(13)
        .fontColor(COLOR_TEXT)
        .lineHeight(19)
        .margin({ top: 10 })

      Progress({ value: Math.round(this.lieConfidence * 100), total: 100, type: ProgressType.Linear })
        .color(COLOR_AMBER)
        .backgroundColor(COLOR_CARD_ALT)
        .height(6)
        .margin({ top: 12 })

      Row() {
        Text('置信度 ' + Math.round(this.lieConfidence * 100) + '%')
          .fontSize(10)
          .fontColor(COLOR_TEXT_DIM)
        Blank()
        Text('平面重力 ' + this.lieInPlane.toFixed(1) + ' m/s²')
          .fontSize(10)
          .fontColor(COLOR_TEXT_DIM)
      }
      .width('100%')
      .margin({ top: 6 })

      Button('锁定当前方向')
        .fontSize(13)
        .fontColor('#1A1206')
        .backgroundColor(COLOR_AMBER)
        .height(38)
        .width('100%')
        .margin({ top: 12 })
        .borderRadius(RADIUS_BUTTON)
        .onClick(() => {
          this.doLockCurrent();
        })

      Text('传感器结论同样只能应用到本应用窗口；全局锁定需要系统签名，见「说明」。')
        .fontSize(10)
        .fontColor(COLOR_TEXT_DIM)
        .lineHeight(15)
        .margin({ top: 8 })
    }
    .cardStyle()
  }

  @Builder
  commandHint() {
    Column() {
      Text('外部指令（hdc）')
        .fontSize(12)
        .fontColor(COLOR_TEXT_DIM)
      Text(HDC_EXAMPLE)
        .fontSize(10)
        .fontColor(COLOR_TEXT)
        .fontFamily('monospace')
        .lineHeight(16)
        .margin({ top: 6 })
      Text('在「设置」页可一键复制。')
        .fontSize(10)
        .fontColor(COLOR_TEXT_DIM)
        .margin({ top: 6 })
    }
    .width('100%')
    .padding(14)
    .borderRadius(RADIUS_CARD)
    .backgroundColor('#0A1424')
    .border({ width: 1, color: COLOR_STROKE })
  }

  @Builder
  guideTab() {
    Column({ space: 12 }) {
      Column() {
        Text('为什么不能像安卓版那样全局锁定？')
          .fontSize(15)
          .fontWeight(FontWeight.Medium)
          .fontColor(COLOR_TEXT)
        Text('鸿蒙没有向普通第三方应用开放「写系统旋转」的接口。改系统设置需要 MANAGE_SETTINGS（系统应用级），全局旋转锁定接口（screen.setScreenRotationLocked / setOrientation）也只有系统应用能调用，普通应用调用会返回 202。')
          .fontSize(12)
          .fontColor(COLOR_TEXT_DIM)
          .lineHeight(19)
          .margin({ top: 8 })
        Text('本应用能做的：控制自己的窗口方向、给出躺姿建议、把你带到系统正确的开关旁边。')
          .fontSize(12)
          .fontColor(COLOR_TEXT)
          .lineHeight(19)
          .margin({ top: 8 })
      }
      .cardStyle()

      Column() {
        Text('控制中心旋转锁定（系统级，推荐日常使用）')
          .fontSize(14)
          .fontWeight(FontWeight.Medium)
          .fontColor(COLOR_TEXT)
        Text('1. 从屏幕右上角下滑打开控制中心。\n2. 找到「旋转锁定」开关并打开。\n3. 躺姿下锁定后，系统不再根据重力猜方向；侧躺换边时手动关一下再开，或先用本应用把当前画面翻 180° 看效果。')
          .fontSize(12)
          .fontColor(COLOR_TEXT_DIM)
          .lineHeight(20)
          .margin({ top: 10 })
      }
      .cardStyle()

      Column() {
        Text('想让全局锁定真正自动化？')
          .fontSize(14)
          .fontWeight(FontWeight.Medium)
          .fontColor(COLOR_TEXT)
        Text('只有两条路：\n• 系统签名/预置应用：用 full SDK + 系统签名，调用 screen.setScreenRotationLocked、screen.setOrientation。个人开发者走不通 AGC 普通分发。\n• 电脑侧 hdc 自动化：用 uitest uiInput 模拟点控制中心，脆弱且必须连着电脑，只适合折腾。')
          .fontSize(12)
          .fontColor(COLOR_TEXT_DIM)
          .lineHeight(20)
          .margin({ top: 10 })
        Text('详细证据、API 与落地条件见仓库 docs/鸿蒙版-可行性评估.md。')
          .fontSize(11)
          .fontColor(COLOR_AMBER)
          .margin({ top: 10 })
      }
      .cardStyle()

      Column() {
        Text('和安卓版的差异')
          .fontSize(14)
          .fontWeight(FontWeight.Medium)
          .fontColor(COLOR_TEXT)
        Text('• 快速设置磁贴：鸿蒙控制中心不开放三方磁贴。\n• 跨应用悬浮球：需要系统级悬浮窗权限，三方应用拿不到；这里做的是「应用内悬浮球」。\n• 按应用规则：无法读取前台应用包名，暂不提供。\n• 自由多窗/分屏/悬浮窗时，系统会忽略方向策略（调用不报错但不生效），此时按钮会提示「未确认生效」。\n• 常驻通知 / 外部指令：外部 Want 指令可用（见首页示例）。')
          .fontSize(12)
          .fontColor(COLOR_TEXT_DIM)
          .lineHeight(20)
          .margin({ top: 10 })
      }
      .cardStyle()
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  settingsTab() {
    Column({ space: 12 }) {
      Column() {
        Row() {
          Text('方向后端')
            .fontSize(13)
            .fontColor(COLOR_TEXT_DIM)
          Blank()
          Text('本应用窗口')
            .fontSize(13)
            .fontColor(COLOR_AMBER)
        }
        .width('100%')

        Text('本工程使用 public SDK 可用的唯一后端：只作用于本应用主窗口，无法影响其它应用。系统签名版本可替换为全局后端（接口已预留）。')
          .fontSize(11)
          .fontColor(COLOR_TEXT_DIM)
          .lineHeight(17)
          .margin({ top: 8 })

        Text('设备 API 版本：' + this.apiVersion + '（鸿蒙 7 = 26）')
          .fontSize(11)
          .fontColor(COLOR_TEXT_DIM)
          .margin({ top: 8 })

        if (!this.persistenceReady) {
          Text('本地存储不可用：本次的方向与偏好不会被保存')
            .fontSize(11)
            .fontColor(COLOR_RED)
            .margin({ top: 6 })
        }
      }
      .cardStyle()

      Column() {
        Row() {
          Column() {
            Text('启动时恢复上次方向')
              .fontSize(14)
              .fontColor(COLOR_TEXT)
            Text('下次打开本应用时套用上次的窗口方向')
              .fontSize(10)
              .fontColor(COLOR_TEXT_DIM)
              .margin({ top: 2 })
          }
          .alignItems(HorizontalAlign.Start)
          .layoutWeight(1)

          Toggle({ type: ToggleType.Switch, isOn: this.restoreOnLaunch })
            .onChange((isOn: boolean) => {
              this.restoreOnLaunch = isOn;
              this.saveRestoreOnLaunch(isOn);
            })
        }
        .width('100%')

        Divider()
          .color(COLOR_STROKE)
          .margin({ top: 12, bottom: 12 })

        Row() {
          Column() {
            Text('应用内悬浮球')
              .fontSize(14)
              .fontColor(COLOR_TEXT)
            Text('单击循环：跟随 → 锁定当前 → 翻转；可拖动并记住位置')
              .fontSize(10)
              .fontColor(COLOR_TEXT_DIM)
              .margin({ top: 2 })
          }
          .alignItems(HorizontalAlign.Start)
          .layoutWeight(1)

          Toggle({ type: ToggleType.Switch, isOn: this.floatEnabled })
            .onChange((isOn: boolean) => {
              this.floatEnabled = isOn;
              this.saveFloatEnabled(isOn);
            })
        }
        .width('100%')
      }
      .cardStyle()

      Button('打开系统「显示和亮度」')
        .fontSize(14)
        .fontColor(COLOR_TEXT)
        .backgroundColor(COLOR_CARD_ALT)
        .border({ width: 1, color: COLOR_STROKE })
        .height(46)
        .width('100%')
        .borderRadius(RADIUS_BUTTON)
        .onClick(() => {
          this.openDisplaySettings();
        })

      Button('复制 hdc 外部指令')
        .fontSize(14)
        .fontColor(COLOR_TEXT)
        .backgroundColor(COLOR_CARD_ALT)
        .border({ width: 1, color: COLOR_STROKE })
        .height(46)
        .width('100%')
        .borderRadius(RADIUS_BUTTON)
        .onClick(() => {
          this.copyHdcCommand();
        })

      Text('隐私：本应用不申请网络权限，不读取屏幕内容；仅在本机保存方向偏好与悬浮球位置。')
        .fontSize(10)
        .fontColor(COLOR_TEXT_DIM)
        .lineHeight(16)
        .width('100%')
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  bottomBar() {
    Row() {
      this.tabItem(0, '控制')
      this.tabItem(1, '说明')
      this.tabItem(2, '设置')
    }
    .width('100%')
    .height(BOTTOM_BAR_HEIGHT)
    .backgroundColor('#0A1220')
    .border({ width: { top: 1 }, color: COLOR_STROKE })
    .padding({ left: PAGE_PADDING, right: PAGE_PADDING, bottom: 8 })
  }

  @Builder
  tabItem(index: number, label: string) {
    Column() {
      Text(label)
        .fontSize(13)
        .fontColor(this.tabIndex === index ? COLOR_AMBER : COLOR_TEXT_DIM)
        .fontWeight(this.tabIndex === index ? FontWeight.Medium : FontWeight.Normal)
      Divider()
        .width(18)
        .strokeWidth(2)
        .color(this.tabIndex === index ? COLOR_AMBER : '#00000000')
        .margin({ top: 5 })
    }
    .layoutWeight(1)
    .height('100%')
    .justifyContent(FlexAlign.Center)
    .onClick(() => {
      this.tabIndex = index;
    })
  }

  @Builder
  floatBall() {
    Stack() {
      Circle()
        .width(56)
        .height(56)
        .fill(COLOR_AMBER)
      Text('⟳')
        .fontSize(26)
        .fontColor('#1A1206')
        .fontWeight(FontWeight.Bold)
    }
    .width(56)
    .height(56)
    .position({ x: this.floatX < 0 ? 0 : this.floatX, y: this.floatY < 0 ? 0 : this.floatY })
    .zIndex(20)
    .shadow({ radius: 16, color: '#66000000', offsetY: 6 })
    .gesture(
      GestureGroup(GestureMode.Exclusive,
        PanGesture({ fingers: 1, distance: 8 })
          .onActionStart(() => {
            this.floatStartX = this.floatX;
            this.floatStartY = this.floatY;
          })
          .onActionUpdate((event: GestureEvent) => {
            const maxX = Math.max(0, this.screenW - 56);
            const maxY = Math.max(0, this.screenH - 140);
            this.floatX = Math.min(Math.max(0, this.floatStartX + event.offsetX), maxX);
            this.floatY = Math.min(Math.max(0, this.floatStartY + event.offsetY), maxY);
          })
          .onActionEnd(() => {
            const prefs = this.prefs;
            if (prefs !== undefined) {
              prefs.saveFloatingPosition(this.floatX, this.floatY);
            }
          }),
        TapGesture({ count: 1 })
          .onAction(() => {
            this.cycleFromFloat();
          })
      )
    )
  }

  private applyDisplayState(): void {
    const info: DisplayInfo = readDisplayInfo();
    this.rotation = info.rotation;
    this.orientationText = orientationLabel(info.orientation);

    try {
      const w = this.getUIContext().px2vp(info.width);
      const h = this.getUIContext().px2vp(info.height);
      if (w > 0) {
        this.screenW = w;
      }
      if (h > 0) {
        this.screenH = h;
      }
    } catch (error) {
      // 保持默认尺寸
    }

    if (this.floatX < 0) {
      this.floatX = Math.max(0, this.screenW - 84);
    }
    if (this.floatY < 0) {
      this.floatY = Math.max(0, this.screenH * 0.42);
    }
    this.clampFloatPosition();
  }

  private clampFloatPosition(): void {
    const maxX = Math.max(0, this.screenW - 56);
    const maxY = Math.max(0, this.screenH - 140);
    this.floatX = Math.min(Math.max(0, this.floatX), maxX);
    this.floatY = Math.min(Math.max(0, this.floatY), maxY);
  }

  private restoreFloatPosition(): void {
    const prefs = this.prefs;
    if (prefs === undefined) {
      return;
    }
    const pos: number[] = prefs.getFloatingPosition();
    if (pos.length === 2 && pos[0] >= 0 && pos[1] >= 0) {
      this.floatX = pos[0];
      this.floatY = pos[1];
    }
  }

  private startLieDownEngine(): void {
    const ok = this.engine.start((sample: LieDownSample) => {
      this.lieAvailable = true;
      this.lying = sample.lying;
      this.lieConfidence = sample.confidence;
      this.lieInPlane = sample.inPlane;
    });
    this.lieAvailable = ok;
  }

  private async restoreLastTarget(): Promise<void> {
    const service = this.service;
    if (service === undefined) {
      return;
    }
    const pending = service.restoreOnLaunch();
    if (pending === null) {
      return;
    }
    const outcome = await pending;
    this.lastTargetText = rotationTargetLabel(service.getPreferences().getLastTarget());
    this.applyDisplayState();
    if (!outcome.ok) {
      this.message = outcome.message;
      this.messageOk = false;
    }
  }

  private onPendingCommandChanged(): void {
    if (this.pendingCommand.length > 0) {
      this.consumePendingCommand();
    }
  }

  private consumePendingCommand(): void {
    const code = this.pendingCommand;
    if (code.length === 0) {
      return;
    }
    AppStorage.setOrCreate<string>('pendingCommand', '');
    if (code === 'flip') {
      this.doFlip();
      return;
    }
    if (code === 'auto') {
      this.doFollow();
      return;
    }
    if (!isKnownTargetCode(code)) {
      this.message = `未知方向指令「${code}」，已忽略并保持当前方向`;
      this.messageOk = false;
      return;
    }
    this.runApply(parseRotationTarget(code));
  }

  private async runApply(target: RotationTarget): Promise<void> {
    const service = this.service;
    if (service === undefined) {
      return;
    }
    const outcome = await service.apply(target, RotationSource.UI);
    this.message = outcome.message;
    this.messageOk = outcome.ok;
    this.lastTargetText = rotationTargetLabel(service.getPreferences().getLastTarget());
    this.applyDisplayState();
  }

  private async doLockCurrent(): Promise<void> {
    const service = this.service;
    if (service === undefined) {
      return;
    }
    const outcome = await service.lockCurrent(RotationSource.UI);
    this.message = outcome.message;
    this.messageOk = outcome.ok;
    this.lastTargetText = rotationTargetLabel(service.getPreferences().getLastTarget());
    this.applyDisplayState();
  }

  private async doFlip(): Promise<void> {
    const service = this.service;
    if (service === undefined) {
      return;
    }
    const outcome = await service.flip(RotationSource.UI);
    this.message = outcome.message;
    this.messageOk = outcome.ok;
    this.lastTargetText = rotationTargetLabel(service.getPreferences().getLastTarget());
    this.applyDisplayState();
  }

  private async doFollow(): Promise<void> {
    const service = this.service;
    if (service === undefined) {
      return;
    }
    const outcome = await service.followSystem(RotationSource.UI);
    this.message = outcome.message;
    this.messageOk = outcome.ok;
    this.lastTargetText = rotationTargetLabel(service.getPreferences().getLastTarget());
    this.applyDisplayState();
  }

  private cycleFromFloat(): void {
    const service = this.service;
    if (service === undefined) {
      return;
    }
    const fixedTarget: RotationTarget = currentRotationTarget();
    const last = service.getPreferences().getLastTarget();
    if (last === RotationTarget.AUTO) {
      this.runApply(fixedTarget);
    } else if (last === fixedTarget) {
      this.runApply(flippedCurrentRotationTarget());
    } else {
      this.runApply(RotationTarget.AUTO);
    }
  }

  private saveRestoreOnLaunch(enabled: boolean): void {
    const prefs = this.prefs;
    if (prefs !== undefined) {
      prefs.setRestoreOnLaunch(enabled);
    }
  }

  private saveFloatEnabled(enabled: boolean): void {
    const prefs = this.prefs;
    if (prefs !== undefined) {
      prefs.setFloatingBallEnabled(enabled);
    }
  }

  private openDisplaySettings(): void {
    const ctx = this.context;
    if (ctx === undefined) {
      return;
    }
    const ok = openDisplaySettingsPage(ctx);
    if (!ok) {
      this.message = '当前系统不支持直接跳转，请打开：设置 > 显示和亮度';
      this.messageOk = false;
      return;
    }
    this.message = '已请求打开系统「显示和亮度」；若未跳转，请手动打开 设置 > 显示和亮度';
    this.messageOk = true;
  }

  private copyHdcCommand(): void {
    try {
      const data = pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, HDC_EXAMPLE);
      pasteboard.getSystemPasteboard().setData(data).then(() => {
        this.message = '已复制 hdc 外部指令';
        this.messageOk = true;
      }).catch(() => {
        this.message = '复制失败，请手动长按选择文本';
        this.messageOk = false;
      });
    } catch (error) {
      this.message = '复制失败，请手动长按选择文本';
      this.messageOk = false;
    }
  }
}
