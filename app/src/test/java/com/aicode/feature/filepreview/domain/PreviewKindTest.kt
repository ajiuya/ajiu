package com.aicode.feature.filepreview.domain

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class PreviewKindTest {
    @Test fun kindsMatchThePreviewContract() {
        assertEquals(
            listOf("MARKDOWN", "HTML", "PDF", "IMAGE", "MEDIA", "JSON", "TABLE", "TEXT", "ARCHIVE", "APK", "OFFICE", "BINARY"),
            PreviewKind.entries.map { it.name }
        )
    }

    @Test fun knownExtensionsCoverEveryPreviewKind() {
        val samples = mapOf(
            "readme.md" to PreviewKind.MARKDOWN,
            "page.html" to PreviewKind.HTML,
            "document.pdf" to PreviewKind.PDF,
            "photo.png" to PreviewKind.IMAGE,
            "clip.mp4" to PreviewKind.MEDIA,
            "config.json" to PreviewKind.JSON,
            "data.csv" to PreviewKind.TABLE,
            "source.kt" to PreviewKind.TEXT,
            "bundle.zip" to PreviewKind.ARCHIVE,
            "app.apk" to PreviewKind.APK,
            "report.docx" to PreviewKind.OFFICE,
            "program.bin" to PreviewKind.BINARY
        )
        samples.forEach { (path, kind) -> assertEquals(path, kind, PreviewTypes.kind(path)) }
    }

    @Test fun caseQueryAndFragmentDoNotChangeTheRealExtension() {
        val samples = mapOf(
            "/tmp/README.MD?download=photo.png#app.apk" to PreviewKind.MARKDOWN,
            "https://files.example/document.PDF#section?name=file.txt" to PreviewKind.PDF,
            "content://files/PHOTO.JpEg?displayName=notes.txt" to PreviewKind.IMAGE,
            "file:///tmp/index.HTML#image.png" to PreviewKind.HTML,
            "~/workspace/records.TSV?token=a#b" to PreviewKind.TABLE
        )
        samples.forEach { (path, kind) -> assertEquals(path, kind, PreviewTypes.kind(path, "text/plain")) }
    }

    @Test fun parentDirectoryExtensionsAreNotFileExtensions() {
        assertEquals(PreviewKind.BINARY, PreviewTypes.kind("/tmp/report.pdf/download"))
        assertEquals(PreviewKind.TEXT, PreviewTypes.kind("/tmp/image.png/README"))
        assertEquals(PreviewKind.TEXT, PreviewTypes.kind("C:\\folder.pdf\\source.kt"))
        assertEquals(PreviewKind.BINARY, PreviewTypes.kind("/tmp/folder.pdf/", "application/pdf"))
        assertEquals(PreviewKind.BINARY, PreviewTypes.kind("", "text/html"))
    }

    @Test fun textFileNamesAndDotFilesAreRecognized() {
        listOf(
            "LICENSE", "LICENCE", "LICENSE-MIT", "COPYING", "COPYRIGHT", "NOTICE", "AUTHORS", "README",
            "CHANGELOG", "CONTRIBUTING", "CODE_OF_CONDUCT", "Dockerfile", "Dockerfile.debug", "Containerfile",
            "Makefile", "GNUmakefile", "CMakeLists.txt", "Gemfile", "Rakefile", "Procfile", "justfile",
            ".gitignore", ".gitattributes", ".gitmodules", ".gitkeep", ".editorconfig", ".npmignore",
            ".dockerignore", ".env", ".env.production", ".bashrc", ".zshrc", ".profile", ".npmrc",
            ".yarnrc", ".python-version", ".tool-versions", ".eslintrc", ".prettierrc"
        ).forEach { name ->
            assertEquals(name, PreviewKind.TEXT, PreviewTypes.kind("/repo/$name", "application/pdf"))
            assertEquals(name, "text/plain", PreviewTypes.mime("/repo/$name", "image/png"))
        }
    }

    @Test fun sourceCodeAndConfigurationExtensionsStayText() {
        val extensions = (
            "kt kts java c h cc cpp cxx hpp hh hxx inl m mm cs fs fsx vb py pyw pyi js jsx mjs cjs " +
                "ts tsx mts cts vue svelte astro go rs swift dart scala sc r rb rake gemspec erb php phtml " +
                "ex exs erl hrl clj cljs cljc edn el lisp lsp scm ss rkt ml mli hs lhs lua pl pm t sql " +
                "sh bash zsh fish ps1 psm1 psd1 bat cmd asm s v sv vhd vhdl f for f90 f95 d pas pp vala " +
                "ino pde zig sol css scss sass less styl xml xsd xsl xslt plist resx props targets yaml yml " +
                "toml ini cfg conf config properties env gradle make mk cmake groovy tf tfvars hcl nix " +
                "tex bib rst adoc asciidoc org lock sum mod srt vtt ass graphql gql proto jsonc jsonl ndjson"
            ).split(' ')
        extensions.forEach { extension ->
            assertEquals(extension, PreviewKind.TEXT, PreviewTypes.kind("/src/file.$extension", "application/zip"))
            val mime = when (extension) {
                "css" -> "text/css"
                "js", "mjs", "cjs" -> "application/javascript"
                else -> "text/plain"
            }
            assertEquals(extension, mime, PreviewTypes.mime("/src/file.$extension"))
        }
    }

    @Test fun svgUsesSourceTextRatherThanRasterOrHtmlRenderers() {
        assertEquals(PreviewKind.TEXT, PreviewTypes.kind("image.SVG", "image/png"))
        assertEquals("image/svg+xml", PreviewTypes.mime("image.svg", "text/plain"))
        assertEquals(PreviewKind.TEXT, PreviewTypes.kind("download", "IMAGE/SVG+XML; charset=utf-8"))
        assertEquals(PreviewKind.BINARY, PreviewTypes.kind("image.svgz", "image/svg+xml"))
    }

    @Test fun rasterExtensionsHaveSpecificMimeTypes() {
        mapOf(
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
            "webp" to "image/webp", "bmp" to "image/bmp", "ico" to "image/x-icon", "avif" to "image/avif",
            "heic" to "image/heic", "heif" to "image/heif", "tiff" to "image/tiff"
        ).forEach { (extension, mime) ->
            assertEquals(extension, PreviewKind.IMAGE, PreviewTypes.kind("file.$extension"))
            assertEquals(extension, mime, PreviewTypes.mime("file.$extension", "text/html"))
        }
    }

    @Test fun mediaExtensionsAndManifestsAreRecognized() {
        listOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "weba", "mp4", "mkv", "webm", "mov",
            "avi", "mpeg", "m2ts", "flv", "ogv", "3gp", "3g2", "wmv", "rmvb", "m3u", "m3u8", "mpd").forEach {
            assertEquals(it, PreviewKind.MEDIA, PreviewTypes.kind("/media/file.$it", "text/plain"))
        }
        assertEquals("audio/mpeg", PreviewTypes.mime("track.mp3"))
        assertEquals("video/mp4", PreviewTypes.mime("clip.mp4"))
        assertEquals("application/vnd.apple.mpegurl", PreviewTypes.mime("stream.m3u8"))
    }

    @Test fun apkRequiresAnApkPathEvenWhenMimeSuggestsInstallation() {
        assertEquals(PreviewKind.APK, PreviewTypes.kind("app.APK", "application/zip"))
        assertEquals("application/vnd.android.package-archive", PreviewTypes.mime("app.apk", "application/zip"))
        listOf("download", "app.apks", "app.xapk", "app.apkm", "app.aab", "app.apk.exe").forEach {
            assertEquals(it, PreviewKind.BINARY, PreviewTypes.kind(it, "application/vnd.android.package-archive"))
            assertEquals(it, "application/octet-stream", PreviewTypes.mime(it, "application/vnd.android.package-archive"))
        }
    }

    @Test fun zipJarAndAarAreArchivesRegardlessOfApkMime() {
        listOf("zip", "jar", "aar").forEach {
            assertEquals(it, PreviewKind.ARCHIVE, PreviewTypes.kind("bundle.$it", "application/vnd.android.package-archive"))
        }
        assertEquals("application/java-archive", PreviewTypes.mime("library.jar"))
        assertEquals("application/zip", PreviewTypes.mime("library.aar"))
    }

    @Test fun unsupportedArchiveAndExecutableSuffixesNeverUseMimeToUpgrade() {
        listOf("tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "zst", "exe", "dll", "so", "dex", "class", "bin").forEach {
            listOf("application/zip", "text/html", "image/png", "text/plain").forEach { mime ->
                assertEquals("$it $mime", PreviewKind.BINARY, PreviewTypes.kind("file.$it", mime))
                assertEquals("$it $mime", "application/octet-stream", PreviewTypes.mime("file.$it", mime))
            }
        }
    }

    @Test fun binarySuffixesWinEvenForTextNameVariants() {
        listOf(".env.production.bin", ".env.gz", "Dockerfile.exe", "LICENSE-MIT.tar", "Containerfile.apks").forEach {
            assertEquals(it, PreviewKind.BINARY, PreviewTypes.kind(it, "text/plain"))
            assertEquals(it, "application/octet-stream", PreviewTypes.mime(it, "text/plain"))
        }
    }

    @Test fun unknownSuffixesDoNotUseMimeFallback() {
        assertEquals(PreviewKind.BINARY, PreviewTypes.kind("file.unknown", "application/pdf"))
        assertEquals(PreviewKind.BINARY, PreviewTypes.kind(".unknown", "text/plain"))
        assertEquals("application/octet-stream", PreviewTypes.mime("file.unknown", "image/png"))
    }

    @Test fun extensionlessPathsAllowKnownCompatibleMimeTypes() {
        mapOf(
            "text/markdown" to PreviewKind.MARKDOWN, "text/html" to PreviewKind.HTML,
            "application/xhtml+xml" to PreviewKind.HTML, "application/pdf" to PreviewKind.PDF,
            "image/png" to PreviewKind.IMAGE, "audio/mpeg" to PreviewKind.MEDIA,
            "video/mp4" to PreviewKind.MEDIA, "application/dash+xml" to PreviewKind.MEDIA,
            "application/json" to PreviewKind.JSON, "application/problem+json" to PreviewKind.JSON,
            "text/csv" to PreviewKind.TABLE, "text/tab-separated-values" to PreviewKind.TABLE,
            "text/plain" to PreviewKind.TEXT, "application/xml" to PreviewKind.TEXT,
            "text/x-kotlin" to PreviewKind.TEXT, "application/zip" to PreviewKind.ARCHIVE,
            "application/java-archive" to PreviewKind.ARCHIVE, "application/msword" to PreviewKind.OFFICE
        ).forEach { (mime, kind) ->
            assertEquals(mime, kind, PreviewTypes.kind("https://files.example/download?id=7#file.apk", mime))
        }
    }

    @Test fun malformedUnknownAndWildcardMimeTypesStayBinary() {
        listOf("", "*/*", "image/*", "image/unknown", "audio/unknown", "application/octet-stream",
            "application/x-shockwave-flash", "application/x-tar", "not a mime", "text/plain\r\nX-Test: value",
            "text/pla\u0000in", "text/", "text/plain extra").forEach {
            assertEquals(it, PreviewKind.BINARY, PreviewTypes.kind("download", it))
            assertEquals(it, "application/octet-stream", PreviewTypes.mime("download", it))
        }
    }

    @Test fun mimeAliasesAndParametersAreNormalized() {
        mapOf(
            " IMAGE/VND.MICROSOFT.ICON; charset=UTF-8 " to "image/x-icon",
            "Application/X-Zip-Compressed" to "application/zip",
            "Text/X-Markdown; charset=utf-8" to "text/markdown",
            "audio/x-mpegurl" to "application/vnd.apple.mpegurl",
            "TEXT/PLAIN; charset=utf-16" to "text/plain",
            "application/problem+json; charset=utf-8" to "application/problem+json"
        ).forEach { (fallback, expected) -> assertEquals(fallback, expected, PreviewTypes.mime("download", fallback)) }
    }

    @Test fun realSuffixWinsOverContradictoryFallbackForBothApis() {
        assertEquals(PreviewKind.TEXT, PreviewTypes.kind("source.ts", "video/mp2t"))
        assertEquals("text/plain", PreviewTypes.mime("source.ts", "video/mp2t"))
        assertEquals(PreviewKind.PDF, PreviewTypes.kind("report.pdf", "image/png"))
        assertEquals("application/pdf", PreviewTypes.mime("report.pdf", "image/png"))
        assertEquals(PreviewKind.JSON, PreviewTypes.kind("data.json", "text/html"))
        assertEquals("application/json", PreviewTypes.mime("data.json", "text/html"))
    }

    @Test fun displayTitleDoesNotChooseThePreviewTypeOrMime() {
        listOf(
            PreviewRequest(path = "/tmp/actual.pdf", title = "image.png", mimeType = "image/png") to PreviewKind.PDF,
            PreviewRequest(path = "/tmp/actual.txt", title = "installer.apk", mimeType = "application/zip") to PreviewKind.TEXT,
            PreviewRequest(path = "/tmp/actual.bin", title = "README.md", mimeType = "text/markdown") to PreviewKind.BINARY,
            PreviewRequest(path = "/tmp/actual.svg", title = "photo.jpg", mimeType = "image/jpeg") to PreviewKind.TEXT
        ).forEach { (request, expected) ->
            assertEquals(request.title, expected, PreviewTypes.kind(request.path, request.mimeType))
            assertEquals(PreviewTypes.mime(request.path), PreviewTypes.mime(request.path, request.mimeType))
        }
    }

    @Test fun officeFormatsAreNotZipOrTextPreviews() {
        listOf("doc", "docx", "dotx", "xls", "xlsx", "xltx", "ppt", "pptx", "ppsx", "odt", "ods", "odp", "rtf").forEach {
            assertEquals(it, PreviewKind.OFFICE, PreviewTypes.kind("report.$it", "application/zip"))
        }
        assertEquals(PreviewKind.TABLE, PreviewTypes.kind("table.csv", "application/vnd.ms-excel"))
    }

    @Test fun webResourceSuffixesHaveTheirRealMimeWithoutChangingTextOrBinaryKinds() {
        mapOf(
            "css" to "text/css", "js" to "application/javascript", "mjs" to "application/javascript",
            "cjs" to "application/javascript", "woff" to "font/woff", "woff2" to "font/woff2",
            "ttf" to "font/ttf", "otf" to "font/otf"
        ).forEach { (extension, mime) ->
            val path = "/assets/resource.${extension.uppercase(Locale.ROOT)}?name=readme.md"
            assertEquals(extension, mime, PreviewTypes.mime(path, "text/html"))
            val kind = if (mime.startsWith("font/")) PreviewKind.BINARY else PreviewKind.TEXT
            assertEquals(extension, kind, PreviewTypes.kind(path, "text/html"))
        }
    }

    @Test fun extensionNormalizationDoesNotDependOnDefaultLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(PreviewKind.IMAGE, PreviewTypes.kind("IMAGE.GIF"))
            assertEquals(PreviewKind.TEXT, PreviewTypes.kind(".GITIGNORE"))
            assertEquals("image/gif", PreviewTypes.mime("download", "IMAGE/GIF"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
