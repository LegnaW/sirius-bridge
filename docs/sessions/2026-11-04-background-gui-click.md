# 失焦 GUI 点击

协议 1.13 为 input.click 增加可选 at {x,y}。主线程校验 Screen、遮罩、坐标和权限，按 NeoForge 屏幕事件顺序派发。后续事件绑定原界面。无 at 使用原入口。

真机验收覆盖窗口失焦但未最小化、原版及模组按钮、界面切换。

验证：Gradle offline build 通过，smoke 750 项全通过，git diff --check 通过。真机待验。
