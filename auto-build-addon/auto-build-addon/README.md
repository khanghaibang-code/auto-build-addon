# Auto Build – addon cho Meteor Client (Minecraft 1.21.11)

Module `auto-build` (mục **World**) tự xây schematic `.litematic` / `.schem` / `.nbt`:
đặt đúng block state, tự sneak khi cần, đặt nước/lava bằng xô, đặt block phụ để có chỗ tựa
và tự đi tới vị trí nằm ngoài tầm với.

## Cách ra file .jar

### Cách 1 – không cần cài gì (GitHub Actions)
1. Tạo repo mới trên GitHub, tải toàn bộ thư mục này lên (giữ nguyên `.github/`).
2. Vào tab **Actions** -> workflow **Build jar** -> chờ chạy xong.
3. Tải artifact `auto-build-addon` -> bên trong là file `.jar`.

### Cách 2 – build trên máy
Cần JDK 21 và Gradle 8.14+ (hoặc import vào IntelliJ IDEA như một dự án Gradle):

    gradle build

File jar nằm ở `build/libs/auto-build-addon-1.0.0.jar`.
(Nếu bạn đã có `gradlew` từ template Meteor thì dùng `./gradlew build`.)

## Cài vào game
1. Cài Fabric Loader cho Minecraft 1.21.11 và Meteor Client 1.21.11.
2. Bỏ file jar vào `.minecraft/mods` cạnh Meteor Client.
3. Đặt schematic vào `.minecraft/schematics`.
4. Trong game: bật GUI Meteor -> World -> `auto-build` -> điền tên file ở `schematic`.
   Đứng ở góc thấp nhất (x/y/z nhỏ nhất) của vị trí muốn xây rồi bật module.

## Dùng bản 1.21.x khác
Sửa 4 dòng trong `gradle.properties` (`minecraft_version`, `yarn_mappings`, `loader_version`,
`meteor_version`) cho khớp bản Meteor bạn dùng (xem gradle.properties của Meteor Client).
Một số tên class/hàm có thể khác nhẹ giữa các bản; nếu build báo lỗi, gửi nguyên văn lỗi để sửa.

## Lưu ý
- Minecraft 26.x dùng tên class khác (không còn Yarn) nên project này CHƯA chạy trên 26.x.
- Addon liên kết với Meteor Client (GPL-3.0), nếu phát hành lại hãy giữ giấy phép GPL-3.0.
- Dùng ở singleplayer hoặc server cho phép; xoay đầu/click của module có thể bị anti-cheat phát hiện.
