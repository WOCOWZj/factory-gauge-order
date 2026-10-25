把整合包 `mods/` 里的 Create 本体 jar 放到这个目录，用于**编译期**依赖：

    libs/create-1.21.1-6.0.11.jar

要求：
- 必须是 1.21.1 / NeoForge 21.1.x 的 Create 6.0.9+（jar 文件名不一定长这样，能对上版本即可）；
- 只需要 jar 本体，不需要 sources / api 变体；
- `build.gradle` 用 `compileOnly fileTree(dir: 'libs', include: ['*.jar'])` 引入，
  不会被打进本模组的 jar，运行时由整合包提供 Create。

如果不想放 jar，可以改用 Create 官方 maven（需联网），见 build.gradle 里注释掉的
`compileOnly "com.simibubi.create:create-1.21.1:6.0.11"`。
