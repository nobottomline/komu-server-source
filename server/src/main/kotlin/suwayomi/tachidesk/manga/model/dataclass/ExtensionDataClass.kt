package suwayomi.tachidesk.manga.model.dataclass

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

data class ExtensionDataClass(
    val repo: String?,
    val apkName: String,
    val iconUrl: String,
    val name: String,
    val pkgName: String,
    val versionName: String,
    val versionCode: Long,
    val lang: String,
    val isNsfw: Boolean,
    val installed: Boolean,
    val hasUpdate: Boolean,
    val obsolete: Boolean,
    val baseUrl: String?,
    val sourceLangs: String?,
    val isCustomPinned: Boolean = false,
    val storeIndexUrl: String? = null,
    val apkUrl: String? = null,
    val jarUrl: String? = null,
    val extensionLib: String? = null,
    val contentWarning: ContentWarning = if (isNsfw) ContentWarning.MIXED else ContentWarning.SAFE,
)
