package org.example.app

/**
 * Simple configuration holder for runtime settings.
 *
 * Note: In a production build this should be sourced from BuildConfig or a managed
 * environment/config mechanism. For now we keep it minimal and dependency-free.
 */
internal object AppConfig {
    /**
     * Base URL for the companion backend.
     *
     * Emulator note: If the backend runs on the development machine, Android emulator
     * usually reaches it via http://10.0.2.2:3001 instead of localhost.
     */
    val backendBaseUrl: String = "http://localhost:3001"
}
