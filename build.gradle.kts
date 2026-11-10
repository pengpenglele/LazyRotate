// 根构建脚本。
// 版本组合由 docs/CONTRACT.md 第 2 节冻结：AGP 9.4.1 + Kotlin 2.4.21 + Gradle 9.8.1 + JDK 17。
// 注意（实测）：AGP 9.x 自带 Kotlin 编译支持，**不能**再声明/应用 org.jetbrains.kotlin.android，
// 否则构建直接 fatal。Compose 编译器插件（版本必须与内置 Kotlin 一致 = 2.4.21）仍要单独声明。
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
