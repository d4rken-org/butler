package eu.darken.butler.apps.core.arguments

/**
 * Available tabs in the App Details workspace
 */
enum class DetailTab {
    /**
     * Overview tab showing basic app info, storage locations, and quick actions
     */
    OVERVIEW,

    /**
     * Package info tab showing APK details, manifest, components, signing info
     */
    PACKAGE_INFO,

    /**
     * Components tab showing activities, services, receivers, and providers
     */
    COMPONENTS,
}
