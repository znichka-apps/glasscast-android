package com.glasscast.sender

// v1 sends supported web links only. Keep all local/LAN serving UI and entry points disabled.
// Re-enabling this experiment requires a fresh permission, foreground-service, and local-network
// review for the then-current Android target SDK before its service is restored to the manifest.
const val ENABLE_LOCAL_VIDEO_EXPERIMENT = false
