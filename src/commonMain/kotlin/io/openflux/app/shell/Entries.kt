package io.openflux.app.shell

import androidx.navigation3.runtime.NavEntry
import io.openflux.app.add.AddKeyScreen
import io.openflux.app.add.GiveScreen
import io.openflux.app.add.ShareRoot
import io.openflux.app.account.AccountScreen
import io.openflux.app.account.SignInScreen
import io.openflux.app.home.HomeRoot
import io.openflux.app.settings.JournalScreen
import io.openflux.app.settings.ScriptsScreen
import io.openflux.app.settings.SettingsPageScreen
import io.openflux.app.settings.SettingsRoot
import io.openflux.app.nav.AppNavigator
import io.openflux.app.profiles.ProfileEditScreen
import io.openflux.app.profiles.ProfileScreen
import io.openflux.app.nav.Dest
import io.openflux.app.nav.ListDetail
import io.openflux.app.nav.Section
import io.openflux.app.nav.destEntry
import io.openflux.app.servers.AddServerScreen
import io.openflux.app.servers.HostingScreen
import io.openflux.app.servers.NewHostingScreen
import io.openflux.app.servers.NewNodeScreen
import io.openflux.app.servers.NodeScreen
import io.openflux.app.servers.ServerScreen
import io.openflux.app.servers.ServersRoot
import io.openflux.app.servers.ThisDeviceScreen

/** The one place that says which screen shows which destination, and which are lists and details. */
fun appEntry(dest: Dest, navigator: AppNavigator): NavEntry<Dest> = when (dest) {
    Dest.Home -> destEntry(dest) { HomeRoot() }
    is Dest.Profile -> destEntry(dest) { ProfileScreen(dest) }
    is Dest.ProfileEdit -> destEntry(dest) { ProfileEditScreen(dest) }
    Dest.AddKey -> destEntry(dest) { AddKeyScreen() }
    Dest.Scan -> destEntry(dest) { AddKeyScreen(scan = true) }
    Dest.Share -> destEntry(dest) { ShareRoot() }
    is Dest.Give -> destEntry(dest) { GiveScreen(dest) }
    Dest.Servers -> destEntry(dest, ListDetail.list(Section.Servers)) { ServersRoot(selected = navigator.stack(Section.Servers).last()) }
    Dest.AddServer -> destEntry(dest) { AddServerScreen() }
    is Dest.Server -> destEntry(dest, ListDetail.detail(Section.Servers)) { ServerScreen(dest) }
    is Dest.Node -> destEntry(dest, ListDetail.detail(Section.Servers)) { NodeScreen(dest) }
    is Dest.NewNode -> destEntry(dest) { NewNodeScreen(dest) }
    Dest.NewHosting -> destEntry(dest) { NewHostingScreen() }
    is Dest.Hosting -> destEntry(dest, ListDetail.detail(Section.Servers)) { HostingScreen(dest) }
    Dest.ThisDevice -> destEntry(dest, ListDetail.detail(Section.Servers)) { ThisDeviceScreen() }
    Dest.Auth -> destEntry(dest) { SignInScreen() }
    Dest.Account -> destEntry(dest, ListDetail.detail(Section.Settings)) { AccountScreen() }
    Dest.Settings -> destEntry(dest, ListDetail.list(Section.Settings)) { SettingsRoot(selected = navigator.stack(Section.Settings).last()) }
    is Dest.SettingsPage -> destEntry(dest, ListDetail.detail(Section.Settings)) { SettingsPageScreen(dest) }
    Dest.Logs -> destEntry(dest, ListDetail.detail(Section.Settings)) { JournalScreen() }
    Dest.Scripts -> destEntry(dest) { ScriptsScreen() }
    else -> placeholderEntry(dest, navigator)
}
