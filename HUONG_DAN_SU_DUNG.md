# Hướng Dẫn Toàn Tập: Phát Triển, Quản Lý & Host Extension Cho Mihon (Android) và Tachimanga (iOS)

Tài liệu này hướng dẫn chi tiết từ A-Z quy trình làm việc với mã nguồn dự án Extension (dựa trên kiến trúc **Keiyoushi `libVersion = "1.6"`**), bao gồm:
1. **Quy trình thêm một nguồn truyện mới.**
2. **Cách xử lý khi trang web truyện gốc thay đổi tên miền (Domain).**
3. **Cách đóng gói và tự host Repository trên GitHub cho cả Mihon (Android) và Tachimanga (iOS).**

---

## Mục Lục
- [Phần 1: Các bước thêm một nguồn truyện mới](#phần-1-các-bước-thêm-một-nguồn-truyện-mới)
  - [Bước 1: Khảo sát trang web mục tiêu](#bước-1-khảo-sát-trang-web-mục-tiêu)
  - [Bước 2: Khởi tạo khung thư mục nguồn](#bước-2-khởi-tạo-khung-thư-mục-nguồn)
  - [Bước 3: Tạo bộ Icon chuẩn Android](#bước-3-tạo-bộ-icon-chuẩn-android)
  - [Bước 4: Cấu hình file `build.gradle.kts`](#bước-4-cấu-hình-file-buildgradlekts)
  - [Bước 5: Viết code lấy dữ liệu với `KeiSource`](#bước-5-viết-code-lấy-dữ-liệu-với-keisource)
  - [Bước 6: Các lưu ý & lỗi thường gặp (403 Ảnh, Cloudflare, DTO)](#bước-6-các-lưu-ý--lỗi-thường-gặp-403-ảnh-cloudflare-dto)
  - [Bước 7: Kiểm tra (Lint) và Build ra file APK / JAR](#bước-7-kiểm-tra-lint-và-build-ra-file-apk--jar)
- [Phần 2: Cách xử lý khi web truyện gốc thay đổi Domain](#phần-2-cách-xử-lý-khi-web-truyện-gốc-thay-đổi-domain)
  - [Cách 1: Đổi nhanh trực tiếp trong App (Không cần build lại)](#cách-1-đổi-nhanh-trực-tiếp-trong-app-không-cần-build-lại)
  - [Cách 2: Cập nhật Domain trong Code và phát hành bản cập nhật](#cách-2-cập-nhật-domain-trong-code-và-phát-hành-bản-cập-nhật)
  - [Quy tắc sống còn để không làm mất truyện trong Kệ sách (Library)](#quy-tắc-sống-còn-để-không-làm-mất-truyện-trong-kệ-sách-library)
- [Phần 3: Đóng gói và Host Extension Repo trên GitHub (Cho Mihon & Tachimanga)](#phần-3-đóng-gói-và-host-extension-repo-trên-github-cho-mihon--tachimanga)
  - [1. Hiểu cơ chế hoạt động của Extension Repo trên Mihon & Tachimanga](#1-hiểu-cơ-chế-hoạt-động-của-extension-repo-trên-mihon--tachimanga)
  - [2. Tạo Keystore ký ứng dụng (`signingkey.jks`)](#2-tạo-keystore-ký-ứng-dụng-signingkeyjks)
  - [3. Script Python tự động đóng gói Repo (`build-my-repo.py`)](#3-script-python-tự-động-đóng-gói-repo-build-my-repopy)
  - [4. Tự động hóa 100% với GitHub Actions](#4-tự-động-hóa-100-với-github-actions)
  - [5. Đường dẫn thêm vào Mihon (Android) và Tachimanga (iOS)](#5-đường-dẫn-thêm-vào-mihon-android-và-tachimanga-ios)

---

## Phần 1: Các bước thêm một nguồn truyện mới

### Bước 1: Khảo sát trang web mục tiêu
Trước khi bắt đầu code, mở trang web bằng trình duyệt máy tính và bật **DevTools (F12) → Tab Network / Elements** để xác định:

1. **Trang web có dùng bộ khung (CMS/Theme) phổ biến không?**
   - Kiểm tra xem giao diện web có thuộc các mẫu đã có sẵn trong thư mục `lib-multisrc/` không:
     - **`wpcomics`**: Giao diện kiểu NetTruyen, NhatTruyen, DocTruyen3Q (có class `.ModuleContent`, `.list-chapter`).
     - **`madara`**: Giao diện WordPress Madara chuẩn (gọi `wp-admin/admin-ajax.php`, class `.page-item-detail`, `.wp-manga-chapter`).
     - **`manhwaz`**: Giao diện kiểu SayHentai, MeHentai, UmeTruyen.
     - **`liliana`** / **`flatsome`** / **`fmreader`**: Các mẫu web truyện phổ biến khác.
   - Nếu web dùng đúng 100% các mẫu trên, bạn chỉ cần khai báo `theme = "<tên_theme>"` trong `build.gradle.kts` và chỉ mất 10-20 dòng code.
   - Nếu web tự thiết kế hoặc đã sửa đổi backend (như `truyenvn.my` hay `otruyen.cc`), ta viết một **Individual Extension** kế thừa trực tiếp `KeiSource`.

2. **Xác định 6 luồng dữ liệu cốt lõi:**
   - **Popular (Truyện nổi bật):** URL trang truyện xem nhiều nhất + tham số phân trang (`?page=2` hoặc `/page/2`).
   - **Latest (Mới cập nhật):** URL trang mới cập nhật + tham số phân trang.
   - **Search & Filters (Tìm kiếm & Bộ lọc):** URL tìm kiếm theo từ khóa (`?q=...` hoặc `?search=...`) và các tham số lọc thể loại/trạng thái.
   - **Manga Details (Chi tiết truyện):** Thẻ HTML hoặc API JSON chứa: Tên truyện (`title`), Ảnh bìa (`thumbnail_url`), Tác giả (`author`), Thể loại (`genre`), Tình trạng (`status`), Mô tả (`description`).
   - **Chapter List (Danh sách chương):** Danh sách chương nằm sẵn trong HTML trang truyện hay phải gọi thêm 1 API phụ (ví dụ `POST /webapi/stories/list-chapter`)?
   - **Page List (Danh sách ảnh trong chương):** Các thẻ `<img>` chứa ảnh truyện dùng thuộc tính nào (`src`, `data-src`, `data-original`)? Ảnh có chặn `Referer` hay không?

---

### Bước 2: Khởi tạo khung thư mục nguồn

Bạn có thể dùng script có sẵn `ext-bootstrap.py` ở thư mục gốc của dự án để tự động tạo cấu trúc chuẩn:

```powershell
# Trường hợp 1: Nguồn độc lập (không dùng multisrc theme)
python ext-bootstrap.py -n "Ten Nguon" -l vi -u https://tennguon.com -c MIXED

# Trường hợp 2: Nguồn kế thừa từ một theme có sẵn trong lib-multisrc (ví dụ madara hoặc wpcomics)
python ext-bootstrap.py -n "Ten Nguon" -l vi -u https://tennguon.com -c SAFE -m wpcomics
```

Cấu trúc thư mục chuẩn được tạo ra tại `src/vi/<tennguon>/`:
```text
src/vi/tennguon/
├── build.gradle.kts
├── res/
│   ├── mipmap-mdpi/ic_launcher.png      (48x48)
│   ├── mipmap-hdpi/ic_launcher.png      (72x72)
│   ├── mipmap-xhdpi/ic_launcher.png     (96x96)
│   ├── mipmap-xxhdpi/ic_launcher.png    (144x144)
│   └── mipmap-xxxhdpi/ic_launcher.png   (192x192)
└── src/eu/kanade/tachiyomi/extension/vi/tennguon/
    ├── TenNguon.kt       (File xử lý chính)
    ├── Dto.kt            (Tùy chọn: Các class đọc JSON API)
    └── Filters.kt        (Tùy chọn: Các class bộ lọc tìm kiếm)
```

> **Lưu ý quy tắc đặt tên file phụ:** Các file phụ đặt tên ngắn gọn là `Dto.kt`, `Filters.kt` (KHÔNG đặt là `TenNguonDto.kt` hay `TenNguonFilters.kt`).

---

### Bước 3: Tạo bộ Icon chuẩn Android

Nếu bạn có 1 file ảnh gốc vuông (ví dụ `logo.png`), bạn có thể chạy lệnh PowerShell sau ngay tại thư mục gốc dự án để tự động xuất ra đủ 5 kích thước chuẩn cho `src/vi/<tennguon>/res/`:

```powershell
Add-Type -AssemblyName System.Drawing
$extDir = "src/vi/tennguon" # Thay bằng tên thư mục nguồn của bạn
$srcPath = "$extDir/logo.png"
$srcImg = [System.Drawing.Image]::FromFile((Resolve-Path $srcPath))
$sizes = @{
    "mipmap-mdpi" = 48
    "mipmap-hdpi" = 72
    "mipmap-xhdpi" = 96
    "mipmap-xxhdpi" = 144
    "mipmap-xxxhdpi" = 192
}
foreach ($k in $sizes.Keys) {
    $size = $sizes[$k]
    $dir = Join-Path "$extDir/res" $k
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $destPath = Join-Path (Resolve-Path $dir) "ic_launcher.png"
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.DrawImage($srcImg, 0, 0, $size, $size)
    $g.Dispose()
    $bmp.Save($destPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}
$srcImg.Dispose()
Remove-Item $srcPath # Xóa ảnh gốc sau khi tạo xong để tránh lỗi lint
```

---

### Bước 4: Cấu hình file `build.gradle.kts`

Mở `src/vi/<tennguon>/build.gradle.kts` và cấu hình như sau:

```kotlin
import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "TenNguon"
    versionCode = 1
    contentWarning = ContentWarning.MIXED // SAFE, MIXED, hoặc NSFW
    libVersion = "1.6"
    // theme = "wpcomics" // Bỏ comment nếu dùng multisrc theme

    source {
        lang = "vi"
        // Nên dùng custom(...) cho các web Việt Nam hay bị chặn/đổi tên miền
        // để hệ thống tự sinh mục cài đặt đổi URL trong ứng dụng:
        baseUrl {
            custom("https://tennguon.com")
        }
        // Hoặc nếu web có nhiều tên miền chạy song song thì dùng mirrors:
        // baseUrl {
        //     mirrors("https://tennguon.com", "https://tennguon.net")
        // }
    }

    deeplink {
        path("/truyen-tranh/..*")
    }
}
```

> **Quy tắc bắt buộc với KSP Codegen (`libVersion = "1.6"`):**
> `name`, `lang`, `id` và `baseUrl` được khai báo trong khối `source {}` của `build.gradle.kts` sẽ được hệ thống tự động tiêm (inject) vào class. **Tuyệt đối KHÔNG** tự khai báo hay `override val name`, `lang`, `id`, `baseUrl` bên trong file Kotlin `.kt`.

---

### Bước 5: Viết code lấy dữ liệu với `KeiSource`

Class chính của bạn phải được gắn `@Source` và kế thừa `KeiSource` (hoặc class của theme):

```kotlin
package eu.kanade.tachiyomi.extension.vi.tennguon

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

@Source
abstract class TenNguon : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = rateLimit(3)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val doc = client.get("$baseUrl/xem-nhieu?page=$page", Fetch.DOCUMENT).asJsoup()
        // Parse danh sách SManga và trả về MangasPage(mangas, hasNextPage)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val doc = client.get("$baseUrl/moi-cap-nhat?page=$page", Fetch.DOCUMENT).asJsoup()
        // Parse danh sách SManga và trả về MangasPage(mangas, hasNextPage)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        // Xử lý tìm kiếm
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // Trả về SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        // Trả về danh sách Page(index, imageUrl = url)
    }
}
```

---

### Bước 6: Các lưu ý & lỗi thường gặp (403 Ảnh, Cloudflare, DTO)

1. **Lỗi không tải được ảnh bìa hoặc lỗi `HTTP 403` khi đọc chương:**
   - **Trường hợp A (Máy chủ ảnh chặn `Referer`):** Nhiều CDN ảnh (như `imggo.net` của `truyenvn.my`) có thuộc tính `referrerpolicy="no-referrer"` trên thẻ `<img>`. Do `KeiSource` mặc định gắn `Referer: $baseUrl/`, CDN sẽ trả về lỗi `403`.
     - **Cách khắc phục:** Thêm dòng sau vào class nguồn để gỡ bỏ `Referer`:
       ```kotlin
       override fun Headers.Builder.configureHeaders() = removeAll("Referer")
       ```
   - **Trường hợp B (Máy chủ ảnh bắt buộc phải có `Referer` của trang đọc):**
     - Mặc định `KeiSource` đã gửi `Referer: $baseUrl/`, nên hầu hết các trang yêu cầu Referer sẽ tự động chạy tốt mà không cần cấu hình thêm.

2. **Quy tắc viết DTO khi parse JSON (`Dto.kt`):**
   - Dùng `class` thông thường kèm `@Serializable`, **không dùng `data class`** (để giảm dung lượng file build).
   - Dùng hàm `response.parseAs<MyDto>()` của `keiyoushi.utils`, không tự tạo biến `Json` riêng.

3. **Lưu đường dẫn tương đối (`setUrlWithoutDomain`):**
   - Luôn lưu đường dẫn tương đối (ví dụ `/truyen-tranh/ten-bo-truyen`) hoặc slug vào `SManga.url` và `SChapter.url` bằng `setUrlWithoutDomain(element.absUrl("href"))`. Không bao giờ lưu kèm tên miền cứng `https://...` vào `url` để khi web đổi tên miền thì truyện đã lưu trong máy không bị hỏng.

---

### Bước 7: Kiểm tra (Lint) và Build ra file APK / JAR

1. **Tăng tốc độ build local:**
   Mở file [`settings.gradle.kts`](file:///c:/Users/admin/Documents/Mihon-extension/settings.gradle.kts), tạm thời comment `loadAllIndividualExtensions()` và chỉ bật nguồn bạn đang làm:
   ```kotlin
   // loadAllIndividualExtensions()
   loadIndividualExtension("vi", "tennguon")
   ```
2. **Chạy lệnh tự động format, kiểm tra lỗi và đóng gói:**
   ```powershell
   .\gradlew.bat :src:vi:tennguon:spotlessApply :src:vi:tennguon:lintRelease :src:vi:tennguon:assembleRelease
   ```
3. **Lấy file đầu ra:**
   - File APK: `src/vi/tennguon/build/outputs/apk/release/tachiyomi-vi.tennguon-v1.6.1.apk`
   - File JAR: `src/vi/tennguon/build/outputs/jar/release/tachiyomi-vi.tennguon-v1.6.1.jar`
   - File Metadata: `src/vi/tennguon/build/keiyoushi-source-info.json`

---

## Phần 2: Cách xử lý khi web truyện gốc thay đổi Domain

Các trang truyện tại Việt Nam rất thường xuyên đổi tên miền (ví dụ từ `.my` sang `.net`, `.org`, `.top`...) khi bị nhà mạng chặn. Có **2 cách xử lý**:

### Cách 1: Đổi nhanh trực tiếp trong App (Không cần build lại)

Nếu trong `build.gradle.kts` của nguồn đó bạn đã khai báo `baseUrl { custom("https://...") }` (như chúng ta đã cấu hình sẵn cho `TruyenVN`), bạn **không cần sửa code hay build lại APK**:
1. Mở ứng dụng **Mihon** hoặc **Tachimanga**.
2. Vào **Browse (Duyệt) → Extensions (Tiện ích mở rộng)** → Bấm vào nguồn **TruyenVN** → Chọn biểu tượng **Cài đặt (Bánh răng)**.
3. Nhập tên miền mới (ví dụ `https://truyenvn.newdomain`) vào ô **Custom base URL** và khởi động lại ứng dụng. Toàn bộ truyện trong kệ sách sẽ tự động hoạt động với tên miền mới!

### Cách 2: Cập nhật Domain trong Code và phát hành bản cập nhật

Khi muốn cập nhật tên miền mới làm mặc định cho tất cả mọi người dùng repo của bạn:

1. Mở file `src/vi/<tennguon>/build.gradle.kts`.
2. **Tăng `versionCode` thêm 1 đơn vị** (ví dụ từ `1` lên `2`) — đây là bước bắt buộc để Mihon/Tachimanga nhận biết có bản cập nhật mới.
3. Thay URL mới vào `baseUrl`:
   ```kotlin
   keiyoushi {
       name = "TruyenVN"
       versionCode = 2 // Tăng từ 1 lên 2
       contentWarning = ContentWarning.MIXED
       libVersion = "1.6"

       source {
           lang = "vi"
           baseUrl {
               custom("https://truyenvn-domain-moi.com") // Cập nhật domain mới tại đây
           }
       }

       deeplink {
           path("/truyen-tranh/..*")
       }
   }
   ```
4. Build lại bản Release:
   ```powershell
   .\gradlew.bat :src:vi:truyenvn:spotlessApply :src:vi:truyenvn:lintRelease :src:vi:truyenvn:assembleRelease
   ```
   *(Lưu ý hay của `custom(...)`: Khi bạn đổi URL mặc định trong code ở bản cập nhật mới, hệ thống tự động xóa URL cũ và áp dụng URL mặc định mới cho người dùng).*

### Quy tắc sống còn để không làm mất truyện trong Kệ sách (Library)
- **KHÔNG đổi `name` hoặc `lang` một cách tùy tiện:** ID định danh của nguồn (`id`) được tính toán tự động từ công thức `MD5(name.lowercase() + "/" + lang + "/" + versionId)`.
- Nếu trang web vừa đổi tên miền **vừa đổi cả tên gọi** (ví dụ bạn muốn đổi `name = "TruyenVN"` thành `name = "TruyenVNMoi"`), bạn **bắt buộc** phải giữ lại `id` cũ bằng cách ghi tường minh `id` cũ vào khối `source {}` (lấy `id` từ file `keiyoushi-source-info.json` hoặc `index.json` trước khi đổi tên):
  ```kotlin
  source {
      name = "TruyenVNMoi"
      lang = "vi"
      id = 5758845301840022147L // Giữ nguyên ID cũ để không mất truyện đã lưu
      baseUrl {
          custom("https://truyenvnmoi.com")
      }
  }
  ```

---

## Phần 3: Đóng gói và Host Extension Repo trên GitHub (Cho Mihon & Tachimanga)

### 1. Hiểu cơ chế hoạt động của Extension Repo trên Mihon & Tachimanga

- **Mihon (Android):** Có thể cài trực tiếp file `.apk` rời HOẶC cài qua link Repo (`index.min.json` / `index.json`).
- **Tachimanga (iOS):** Do iOS không chạy được file `.apk` như hệ điều hành Android, Tachimanga bắt buộc phải đọc qua một **Extension Repository URL** (`index.min.json`). Tachimanga đọc file `index.min.json` để liệt kê danh sách nguồn, sau đó tải gói cài đặt từ thư mục `apk/` của repo về chạy trong môi trường giả lập nội bộ của Tachimanga.
- Để repo của bạn dùng được trên **cả Mihon mới nhất, các bản fork Android (Komikku, TachiyomiSY) VÀ Tachimanga (iOS)**, thư mục publish trên GitHub cần có cấu trúc chuẩn sau:
  ```text
  repo/
  ├── index.min.json    (Chuẩn truyền thống: Tương thích Tachimanga iOS & Mihon Android)
  ├── index.json        (Bản format đẹp của index.min.json)
  ├── apk/
  │   └── tachiyomi-vi.truyenvn-v1.6.1.apk
  ├── jar/
  │   └── tachiyomi-vi.truyenvn-v1.6.1.jar
  └── icon/
      └── eu.kanade.tachiyomi.extension.vi.truyenvn.png
  ```

---

### 2. Tạo Keystore ký ứng dụng (`signingkey.jks`)

Khi phát hành lên Repo, tất cả các file `.apk` và `.jar` cần được ký bằng cùng 1 chứng chỉ số (`signingkey.jks`) để điện thoại cho phép cập nhật phiên bản mới đè lên phiên bản cũ.

Chạy lệnh sau một lần duy nhất ở thư mục gốc dự án để tạo file `signingkey.jks`:

```powershell
keytool -genkeypair -v -keystore signingkey.jks -alias mykey -keyalg RSA -keysize 2048 -validity 10000 -storepass 123456 -keypass 123456 -dname "CN=ExtensionRepo, OU=Dev, O=MyRepo, L=HN, S=HN, C=VN"
```

Khi build local có ký tên, bạn đặt biến môi trường trước khi chạy `gradlew`:
```powershell
$env:ALIAS="mykey"
$env:KEY_STORE_PASSWORD="123456"
$env:KEY_PASSWORD="123456"
.\gradlew.bat assembleRelease
```
*(Nếu không đặt biến môi trường hoặc chưa tạo `signingkey.jks`, hệ thống sẽ tự động ký bằng Debug Key mặc định của máy bạn).*

---

### 3. Script Python tự động đóng gói Repo (`build-my-repo.py`)

Sau khi chạy `.\gradlew.bat assembleRelease`, mỗi nguồn đã được tạo sẵn:
- File `.apk` trong `build/outputs/apk/release/`
- File `.jar` trong `build/outputs/jar/release/`
- File thông tin `build/keiyoushi-source-info.json`
- File icon trong `res/mipmap-xhdpi/ic_launcher.png`

Bạn có thể tạo một file tên là `build-my-repo.py` ở thư mục gốc dự án với nội dung dưới đây. Script này sẽ tự động gom toàn bộ các nguồn bạn đã build và xuất ra thư mục `dist/` hoàn chỉnh (có sẵn `index.min.json`, `index.json`, `apk/`, `jar/`, `icon/`) tương thích với cả **Mihon** và **Tachimanga**:

```python
import json
import shutil
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent
DIST_DIR = ROOT_DIR / "dist"
APK_DIR = DIST_DIR / "apk"
JAR_DIR = DIST_DIR / "jar"
ICON_DIR = DIST_DIR / "icon"

# Tạo các thư mục đầu ra
for d in (DIST_DIR, APK_DIR, JAR_DIR, ICON_DIR):
    d.mkdir(parents=True, exist_ok=True)

index_entries = []

# Quét toàn bộ các nguồn đã được build Release trong src/
for info_path in sorted(ROOT_DIR.glob("src/*/*/build/keiyoushi-source-info.json")):
    info = json.loads(info_path.read_text(encoding="utf-8"))
    build_dir = info_path.parent
    ext_dir = build_dir.parent

    apk_files = list((build_dir / "outputs/apk/release").glob("*.apk"))
    jar_files = list((build_dir / "outputs/jar/release").glob("*.jar"))
    if not apk_files:
        continue

    apk_file = apk_files[0]
    shutil.copy2(apk_file, APK_DIR / apk_file.name)

    if jar_files:
        jar_file = jar_files[0]
        shutil.copy2(jar_file, JAR_DIR / jar_file.name)

    # Copy icon (ưu tiên icon của nguồn, nếu không có thì lấy từ theme hoặc core)
    pkg_name = info["packageName"]
    icon_candidates = [
        ext_dir / "res/mipmap-xhdpi/ic_launcher.png",
        ext_dir / "res/mipmap-xxhdpi/ic_launcher.png",
    ]
    if info.get("theme"):
        icon_candidates.append(
            ROOT_DIR / "lib-multisrc" / info["theme"] / "res/mipmap-xhdpi/ic_launcher.png"
        )
    icon_candidates.append(ROOT_DIR / "core/src/main/res/mipmap-xhdpi/ic_launcher.png")

    for icon_src in icon_candidates:
        if icon_src.exists():
            shutil.copy2(icon_src, ICON_DIR / f"{pkg_name}.png")
            break

    # Chuyển đổi sang chuẩn index.min.json tương thích cả Mihon & Tachimanga
    # contentWarning: 1 = SAFE (nsfw: 0), 2 = MIXED (nsfw: 1), 3 = NSFW (nsfw: 1)
    nsfw_flag = 0 if info.get("contentWarning") == 1 else 1
    sources_list = info.get("sources", [])
    primary_lang = sources_list[0]["lang"] if len(sources_list) == 1 else "all"

    entry = {
        "name": f"Tachiyomi: {info['name']}",
        "pkg": pkg_name,
        "apk": apk_file.name,
        "lang": primary_lang,
        "code": info["versionCode"],
        "version": info["versionName"],
        "nsfw": nsfw_flag,
        "sources": [
            {
                "name": s["name"],
                "lang": s["lang"],
                "id": str(s["id"]),
                "baseUrl": s["baseUrl"],
            }
            for s in sources_list
        ],
    }
    index_entries.append(entry)
    print(f"Đã đóng gói: {info['name']} ({apk_file.name})")

# Xuất file index.min.json và index.json
(DIST_DIR / "index.min.json").write_text(
    json.dumps(index_entries, ensure_ascii=False, separators=(",", ":")),
    encoding="utf-8",
)
(DIST_DIR / "index.json").write_text(
    json.dumps(index_entries, ensure_ascii=False, indent=2),
    encoding="utf-8",
)

print(f"\nHoàn tất! Đã đóng gói {len(index_entries)} nguồn vào thư mục: {DIST_DIR}")
```

---

### 4. Tự động hóa 100% với GitHub Actions

Để mỗi khi bạn thêm/sửa nguồn và `git push` lên nhánh `main`, GitHub tự động build và đẩy file `.apk` + `index.min.json` sang một nhánh tên là `repo`:

1. Trên GitHub cá nhân của bạn, vào **Settings → Actions → General → Workflow permissions**, chọn **Read and write permissions** rồi bấm **Save**.
2. Thay nội dung file `.github/workflows/build_push.yml` (hoặc tạo workflow mới `.github/workflows/publish_my_repo.yml`) như sau:

```yaml
name: Build and Publish My Extension Repo

on:
  push:
    branches:
      - main
  workflow_dispatch:

permissions:
  contents: write

jobs:
  build-and-publish:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout source code
        uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: 21
          distribution: temurin

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Build all remaining extensions
        run: ./gradlew assembleRelease

      - name: Generate repo dist folder
        run: python3 build-my-repo.py

      - name: Deploy to repo branch
        uses: peaceiris/actions-gh-pages@v4
        with:
          github_token: ${{ secrets.GITHUB_TOKEN }}
          publish_dir: ./dist
          publish_branch: repo
```

---

### 5. Đường dẫn thêm vào Mihon (Android) và Tachimanga (iOS)

Sau khi đẩy thư mục `dist/` lên nhánh `repo` trên GitHub của bạn (ví dụ tài khoản GitHub của bạn là `username` và tên repository là `my-extensions`), đường dẫn Extension Repo của bạn sẽ là:

```text
https://raw.githubusercontent.com/<username>/<my-extensions>/repo/index.min.json
```
*(Hoặc qua CDN jsDelivr để tốc độ tải tại Việt Nam nhanh hơn)*:
```text
https://cdn.jsdelivr.net/gh/<username>/<my-extensions>@repo/index.min.json
```

**Cách thêm vào ứng dụng:**
- **Trên Mihon (Android):** Vào **More (Thêm) → Settings (Cài đặt) → Browse (Duyệt) → Extension repos (Kho tiện ích)** → Nhấn **Add (+)** → Dán đường dẫn `index.min.json` ở trên vào.
- **Trên Tachimanga (iOS):** Vào **More → Extensions → Extension Repos** (hoặc biểu tượng góc trên bên phải ở tab Extensions) → Nhấn **Add Repo** → Dán đường dẫn `index.min.json` ở trên vào → Quay lại danh sách Extensions kéo xuống để làm mới và bấm **Install**!

