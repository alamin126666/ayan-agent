pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "AyanAgent"
include(":app")
// Termux terminal libraries (Apache 2.0, see THIRD_PARTY_NOTICES.md):
// terminal-view hosts the terminal UI, terminal-emulator runs the PTY
// session. They are the same code Termux itself uses for its terminal.
include(":terminal-emulator")
include(":terminal-view")
