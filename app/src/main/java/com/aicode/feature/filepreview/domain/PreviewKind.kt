package com.aicode.feature.filepreview.domain

import java.util.Locale

internal enum class PreviewKind {
    MARKDOWN, HTML, PDF, IMAGE, MEDIA, JSON, TABLE, TEXT, ARCHIVE, APK, OFFICE, BINARY
}

internal object PreviewTypes {
    private data class Type(val kind: PreviewKind, val mime: String)

    private val extensions = buildMap {
        listOf(
            "md markdown mdown mkd mkdn rmd qmd" to Type(PreviewKind.MARKDOWN, "text/markdown"),
            "html htm" to Type(PreviewKind.HTML, "text/html"),
            "xhtml xht" to Type(PreviewKind.HTML, "application/xhtml+xml"),
            "svg" to Type(PreviewKind.TEXT, "image/svg+xml"),
            "pdf" to Type(PreviewKind.PDF, "application/pdf"),
            "png" to Type(PreviewKind.IMAGE, "image/png"),
            "jpg jpeg jpe" to Type(PreviewKind.IMAGE, "image/jpeg"),
            "gif" to Type(PreviewKind.IMAGE, "image/gif"),
            "webp" to Type(PreviewKind.IMAGE, "image/webp"),
            "bmp dib" to Type(PreviewKind.IMAGE, "image/bmp"),
            "ico" to Type(PreviewKind.IMAGE, "image/x-icon"),
            "avif" to Type(PreviewKind.IMAGE, "image/avif"),
            "heic" to Type(PreviewKind.IMAGE, "image/heic"),
            "heif" to Type(PreviewKind.IMAGE, "image/heif"),
            "tif tiff" to Type(PreviewKind.IMAGE, "image/tiff"),
            "mp3" to Type(PreviewKind.MEDIA, "audio/mpeg"),
            "flac" to Type(PreviewKind.MEDIA, "audio/flac"),
            "wav wave" to Type(PreviewKind.MEDIA, "audio/wav"),
            "m4a alac" to Type(PreviewKind.MEDIA, "audio/mp4"),
            "aac" to Type(PreviewKind.MEDIA, "audio/aac"),
            "ogg oga opus" to Type(PreviewKind.MEDIA, "audio/ogg"),
            "weba" to Type(PreviewKind.MEDIA, "audio/webm"),
            "wma" to Type(PreviewKind.MEDIA, "audio/x-ms-wma"),
            "ape" to Type(PreviewKind.MEDIA, "audio/x-ape"),
            "aif aiff aifc" to Type(PreviewKind.MEDIA, "audio/aiff"),
            "mid midi" to Type(PreviewKind.MEDIA, "audio/midi"),
            "mp4 m4v" to Type(PreviewKind.MEDIA, "video/mp4"),
            "mkv" to Type(PreviewKind.MEDIA, "video/x-matroska"),
            "webm" to Type(PreviewKind.MEDIA, "video/webm"),
            "mov qt" to Type(PreviewKind.MEDIA, "video/quicktime"),
            "avi" to Type(PreviewKind.MEDIA, "video/x-msvideo"),
            "mpeg mpg mpe" to Type(PreviewKind.MEDIA, "video/mpeg"),
            "m2ts" to Type(PreviewKind.MEDIA, "video/mp2t"),
            "flv" to Type(PreviewKind.MEDIA, "video/x-flv"),
            "ogv" to Type(PreviewKind.MEDIA, "video/ogg"),
            "3gp 3gpp" to Type(PreviewKind.MEDIA, "video/3gpp"),
            "3g2" to Type(PreviewKind.MEDIA, "video/3gpp2"),
            "wmv" to Type(PreviewKind.MEDIA, "video/x-ms-wmv"),
            "rm rmvb" to Type(PreviewKind.MEDIA, "application/vnd.rn-realmedia"),
            "m3u m3u8" to Type(PreviewKind.MEDIA, "application/vnd.apple.mpegurl"),
            "mpd" to Type(PreviewKind.MEDIA, "application/dash+xml"),
            "json ipynb geojson" to Type(PreviewKind.JSON, "application/json"),
            "csv" to Type(PreviewKind.TABLE, "text/csv"),
            "tsv tab" to Type(PreviewKind.TABLE, "text/tab-separated-values"),
            "zip" to Type(PreviewKind.ARCHIVE, "application/zip"),
            "jar" to Type(PreviewKind.ARCHIVE, "application/java-archive"),
            "aar" to Type(PreviewKind.ARCHIVE, "application/zip"),
            "apk" to Type(PreviewKind.APK, "application/vnd.android.package-archive"),
            "doc dot" to Type(PreviewKind.OFFICE, "application/msword"),
            "docx" to Type(PreviewKind.OFFICE, "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            "dotx" to Type(PreviewKind.OFFICE, "application/vnd.openxmlformats-officedocument.wordprocessingml.template"),
            "xls xlt" to Type(PreviewKind.OFFICE, "application/vnd.ms-excel"),
            "xlsx" to Type(PreviewKind.OFFICE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            "xltx" to Type(PreviewKind.OFFICE, "application/vnd.openxmlformats-officedocument.spreadsheetml.template"),
            "ppt pps pot" to Type(PreviewKind.OFFICE, "application/vnd.ms-powerpoint"),
            "pptx" to Type(PreviewKind.OFFICE, "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            "ppsx" to Type(PreviewKind.OFFICE, "application/vnd.openxmlformats-officedocument.presentationml.slideshow"),
            "odt" to Type(PreviewKind.OFFICE, "application/vnd.oasis.opendocument.text"),
            "ods" to Type(PreviewKind.OFFICE, "application/vnd.oasis.opendocument.spreadsheet"),
            "odp" to Type(PreviewKind.OFFICE, "application/vnd.oasis.opendocument.presentation"),
            "rtf" to Type(PreviewKind.OFFICE, "application/rtf"),
            "css" to Type(PreviewKind.TEXT, "text/css"),
            "js mjs cjs" to Type(PreviewKind.TEXT, "application/javascript"),
            "woff" to Type(PreviewKind.BINARY, "font/woff"),
            "woff2" to Type(PreviewKind.BINARY, "font/woff2"),
            "ttf" to Type(PreviewKind.BINARY, "font/ttf"),
            "otf" to Type(PreviewKind.BINARY, "font/otf"),
            "txt text log out err trace diff patch " +
                "kt kts java c h cc cpp cxx hpp hh hxx inl m mm cs fs fsx vb " +
                "py pyw pyi jsx ts tsx mts cts vue svelte astro go rs swift dart scala sc r " +
                "rb rake gemspec erb php phtml ex exs erl hrl clj cljs cljc edn el lisp lsp scm ss rkt " +
                "ml mli hs lhs lua pl pm t sql sh bash zsh fish ps1 psm1 psd1 bat cmd " +
                "asm s v sv vhd vhdl f for f90 f95 d pas pp vala ino pde zig sol " +
                "scss sass less styl xml xsd xsl xslt plist resx props targets " +
                "yaml yml toml ini cfg conf config properties env dotenv " +
                "gradle make mk cmake groovy gvy gtpl tf tfvars hcl nix dockerfile containerfile " +
                "tex bib rst adoc asciidoc org lock sum mod srt vtt ass graphql gql proto " +
                "jsonc jsonl ndjson" to Type(PreviewKind.TEXT, "text/plain")
        ).forEach { (suffixes, type) ->
            suffixes.split(' ').forEach { put(it, type) }
        }
    }

    private val textNames = setOf(
        "license", "licence", "copying", "copyright", "notice", "authors", "readme", "changelog",
        "changes", "contributing", "code_of_conduct", "dockerfile", "containerfile", "makefile",
        "gnumakefile", "cmakelists.txt", "gemfile", "rakefile", "procfile", "justfile", "brewfile",
        "vagrantfile", ".gitignore", ".gitattributes", ".gitmodules", ".gitkeep", ".editorconfig",
        ".npmignore", ".dockerignore", ".eslintignore", ".prettierignore", ".env", ".bashrc",
        ".zshrc", ".profile", ".bash_profile", ".bash_logout", ".zprofile", ".zshenv", ".vimrc",
        ".inputrc", ".curlrc", ".wgetrc", ".npmrc", ".yarnrc", ".nvmrc", ".tool-versions",
        ".python-version", ".ruby-version", ".eslintrc", ".prettierrc", ".babelrc", ".stylelintrc",
        ".browserslistrc"
    )
    private val binaryExtensions = setOf(
        "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "zst", "svgz", "exe", "dll", "so", "dex",
        "class", "bin", "o", "a", "wasm", "db", "sqlite", "sqlite3", "jks", "keystore", "p12",
        "apks", "xapk", "apkm", "aab"
    )
    private val mimeTypes = extensions.values
        .filter { it.kind != PreviewKind.APK }
        .associateBy { it.mime }
    private val mimePattern = Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")
    private val binary = Type(PreviewKind.BINARY, "application/octet-stream")

    fun kind(path: String, mimeType: String = ""): PreviewKind = resolve(path, mimeType).kind

    fun mime(path: String, fallback: String = ""): String = resolve(path, fallback).mime

    private fun resolve(path: String, fallback: String): Type {
        val name = path.substringBefore('?').substringBefore('#')
            .substringAfterLast('/').substringAfterLast('\\').lowercase(Locale.ROOT)
        val suffix = name.substringAfterLast('.', "")
        extensions[suffix]?.let { return it }
        if (suffix in binaryExtensions) return binary
        if (name in textNames || name.startsWith(".env.") ||
            name.startsWith("dockerfile.") || name.startsWith("containerfile.") ||
            name.startsWith("license-") || name.startsWith("licence-")) {
            return Type(PreviewKind.TEXT, "text/plain")
        }
        // 未知后缀不能被 MIME 提升成另一种可执行或主动内容格式。
        if (name.isEmpty() || '.' in name) return binary
        val mime = fallback.substringBefore(';').trim().lowercase(Locale.ROOT)
        if (!mimePattern.matches(mime)) return binary
        mimeTypes[mime]?.let { return it }
        return when {
            mime == "application/x-zip-compressed" -> Type(PreviewKind.ARCHIVE, "application/zip")
            mime == "image/vnd.microsoft.icon" -> Type(PreviewKind.IMAGE, "image/x-icon")
            mime == "text/x-markdown" -> Type(PreviewKind.MARKDOWN, "text/markdown")
            mime == "application/x-mpegurl" || mime == "audio/x-mpegurl" ->
                Type(PreviewKind.MEDIA, "application/vnd.apple.mpegurl")
            mime == "application/xml" || mime == "application/javascript" ||
                mime == "application/x-javascript" || mime == "application/x-yaml" ||
                mime == "application/yaml" || mime.startsWith("text/") -> Type(PreviewKind.TEXT, mime)
            mime.startsWith("application/") && mime.endsWith("+json") -> Type(PreviewKind.JSON, mime)
            else -> binary
        }
    }
}
