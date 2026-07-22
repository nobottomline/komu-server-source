package suwayomi.tachidesk.manga.impl.util

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Serializes extension source/classloader publication and invalidation as one runtime epoch. */
internal object ExtensionRuntimeRegistry {
    private val lock = ReentrantLock()

    fun <T> withLock(action: () -> T): T = lock.withLock(action)
}
