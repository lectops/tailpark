// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd.update

import androidx.core.content.FileProvider

/** Dedicated provider identity avoids merging with diagnostic or upstream sharing providers. */
class UpdateFileProvider : FileProvider()
