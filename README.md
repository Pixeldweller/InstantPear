# InstantPear

Collaborative pair-programming plugin for IntelliJ with a separate screenshare
lobby that renders guest annotations (ghost cursors, click ripples, sticky
notes, attention hints) as a **native, click-through OS overlay** above all
applications on the host machine.

## Screenshare architecture

```
Host (IDE + plugin)                 Guests (Firefox)
┌───────────────────────┐           ┌───────────────────────┐
│  Plugin                │           │  overlay.html          │
│  ├─ PearClient (WS)    │  WS       │  ├─ RTCPeerConnection  │
│  │   (observer)        │◀─────────▶│  │   (view video)      │
│  ├─ OverlayWindow JNA  │  lobby    │  └─ annotation input   │
│  │   (Win32 layered)   │  relay    │                        │
│  └─ BrowserUtil.browse │           └───────────▲────────────┘
│       ↓                │                       │ WebRTC
│  System Firefox        │                       │
│  ├─ getDisplayMedia    │                       │
│  └─ RTCPeerConnection ─┼───────────────────────┘
└───────────────────────┘
```

- Host IDE spawns the user's system default browser pointed at the host page.
- Firefox captures the chosen display and serves it to each guest over WebRTC.
- Plugin joins the same lobby as a silent *observer* so the OS overlay can
  render annotations that guests send.

## LAN WebRTC preparation

WebRTC establishes a direct peer-to-peer connection via ICE. On a LAN this
relies on **host candidates** (raw `192.168.x.y:port`). Firefox obfuscates
those candidates into `*.local` mDNS names by default, which fails to resolve
between peers on many networks and results in `ICE failed`.

Two options:

1. **Disable mDNS candidate obfuscation in Firefox** — on **every**
   participating Firefox (host + every guest):
   - `about:config`, accept the prompt.
   - Set `media.peerconnection.ice.obfuscate_host_addresses` → `false`.
   - Restart Firefox.
   - Peers now exchange raw LAN IPs as ICE candidates and connect directly.
   - Trade-off: exposes the LAN IP to the other lobby members.

2. **Run a TURN server** so ICE falls back to a relay candidate that doesn't
   need host-candidate resolution. See below for the configuration flag.

If you can't touch browser config and don't want a TURN service, use (2).

## coturn TURN server configuration

A config flag in the plugin settings (default **off**) controls whether ICE
candidates advertised to the browser include a TURN server. When on, both the
host page and every guest page receive the TURN URL + credentials via URL
parameters and append the TURN entry to `RTCPeerConnection.iceServers`.

### Plugin settings (InstantPear tool window → *TURN server*)

| Field            | Example                          |
|------------------|----------------------------------|
| Enable TURN      | `false` by default               |
| TURN URL         | `turn:screenshare.example.com:3478` |
| TURN Username    | `pear`                           |
| TURN Password    | `pearpass`                       |

Leaving TURN off keeps the client on STUN-only (suitable once mDNS is
disabled).

### Installing coturn on Linux

```bash
sudo apt install coturn
sudo sed -i 's/#TURNSERVER_ENABLED=1/TURNSERVER_ENABLED=1/' /etc/default/coturn
```

Minimal `/etc/turnserver.conf`:

```
listening-port=3478
min-port=49160
max-port=49200
fingerprint
lt-cred-mech
realm=pear.local
user=pear:pearpass
no-tls
no-dtls
external-ip=<server-public-or-lan-ip>
```

Open firewall ports:

```bash
sudo ufw allow 3478/udp
sudo ufw allow 49160:49200/udp
sudo systemctl enable --now coturn
```

Fill the matching URL/user/pass into the plugin settings, toggle *Enable
TURN*, then start a new lobby. Guests that fail to reach each other directly
will transparently fall back to relaying through coturn.

---

# IntelliJ Platform Plugin Template

[![Twitter Follow](https://img.shields.io/badge/follow-%40JBPlatform-1DA1F2?logo=twitter)](https://twitter.com/JBPlatform)
[![Developers Forum](https://img.shields.io/badge/JetBrains%20Platform-Join-blue)][jb:forum]

## Plugin template structure

A generated project contains the following content structure:

```
.
├── .run/                   Predefined Run/Debug Configurations
├── build/                  Output build directory
├── gradle
│   ├── wrapper/            Gradle Wrapper
├── src                     Plugin sources
│   ├── main
│   │   ├── kotlin/         Kotlin production sources
│   │   └── resources/      Resources - plugin.xml, icons, messages
├── .gitignore              Git ignoring rules
├── build.gradle.kts        Gradle build configuration
├── gradle.properties       Gradle configuration properties
├── gradlew                 *nix Gradle Wrapper script
├── gradlew.bat             Windows Gradle Wrapper script
├── README.md               README
└── settings.gradle.kts     Gradle project settings
```

In addition to the configuration files, the most crucial part is the `src` directory, which contains our implementation
and the manifest for our plugin – [plugin.xml][file:plugin.xml].

> [!NOTE]
> To use Java in your plugin, create the `/src/main/java` directory.

## Plugin configuration file

The plugin configuration file is a [plugin.xml][file:plugin.xml] file located in the `src/main/resources/META-INF`
directory.
It provides general information about the plugin, its dependencies, extensions, and listeners.

You can read more about this file in the [Plugin Configuration File][docs:plugin.xml] section of our documentation.

If you're still not quite sure what this is all about, read our
introduction: [What is the IntelliJ Platform?][docs:intro]

$H$H Predefined Run/Debug configurations

Within the default project structure, there is a `.run` directory provided containing predefined *Run/Debug
configurations* that expose corresponding Gradle tasks:

| Configuration name | Description                                                                                                                                                                         |
|--------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Run Plugin         | Runs [`:runIde`][gh:intellij-platform-gradle-plugin-runIde] IntelliJ Platform Gradle Plugin task. Use the *Debug* icon for plugin debugging.                                        |
| Run Tests          | Runs [`:test`][gradle:lifecycle-tasks] Gradle task.                                                                                                                                 |
| Run Verifications  | Runs [`:verifyPlugin`][gh:intellij-platform-gradle-plugin-verifyPlugin] IntelliJ Platform Gradle Plugin task to check the plugin compatibility against the specified IntelliJ IDEs. |

> [!NOTE]
> You can find the logs from the running task in the `idea.log` tab.

## Publishing the plugin

> [!TIP]
> Make sure to follow all guidelines listed in [Publishing a Plugin][docs:publishing] to follow all recommended and
> required steps.

Releasing a plugin to [JetBrains Marketplace](https://plugins.jetbrains.com) is a straightforward operation that uses
the `publishPlugin` Gradle task provided by
the [intellij-platform-gradle-plugin][gh:intellij-platform-gradle-plugin-docs].

You can also upload the plugin to the [JetBrains Plugin Repository](https://plugins.jetbrains.com/plugin/upload)
manually via UI.

## Useful links

- [IntelliJ Platform SDK Plugin SDK][docs]
- [IntelliJ Platform Gradle Plugin Documentation][gh:intellij-platform-gradle-plugin-docs]
- [IntelliJ Platform Explorer][jb:ipe]
- [JetBrains Marketplace Quality Guidelines][jb:quality-guidelines]
- [IntelliJ Platform UI Guidelines][jb:ui-guidelines]
- [JetBrains Marketplace Paid Plugins][jb:paid-plugins]
- [IntelliJ SDK Code Samples][gh:code-samples]

[docs]: https://plugins.jetbrains.com/docs/intellij

[docs:intro]: https://plugins.jetbrains.com/docs/intellij/intellij-platform.html?from=IJPluginTemplate

[docs:plugin.xml]: https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html?from=IJPluginTemplate

[docs:publishing]: https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html?from=IJPluginTemplate

[file:plugin.xml]: ./src/main/resources/META-INF/plugin.xml

[gh:code-samples]: https://github.com/JetBrains/intellij-sdk-code-samples

[gh:intellij-platform-gradle-plugin]: https://github.com/JetBrains/intellij-platform-gradle-plugin

[gh:intellij-platform-gradle-plugin-docs]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html

[gh:intellij-platform-gradle-plugin-runIde]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html#runIde

[gh:intellij-platform-gradle-plugin-verifyPlugin]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html#verifyPlugin

[gradle:lifecycle-tasks]: https://docs.gradle.org/current/userguide/java_plugin.html#lifecycle_tasks

[jb:github]: https://github.com/JetBrains/.github/blob/main/profile/README.md

[jb:forum]: https://platform.jetbrains.com/

[jb:quality-guidelines]: https://plugins.jetbrains.com/docs/marketplace/quality-guidelines.html

[jb:paid-plugins]: https://plugins.jetbrains.com/docs/marketplace/paid-plugins-marketplace.html

[jb:quality-guidelines]: https://plugins.jetbrains.com/docs/marketplace/quality-guidelines.html

[jb:ipe]: https://jb.gg/ipe

[jb:ui-guidelines]: https://jetbrains.github.io/ui