<div align="center">

# 💀 SkullDrops

**Customizable mob and player head drops for Minecraft Paper servers.**

![Minecraft](https://img.shields.io/badge/Minecraft-26.3-62b47a?style=flat-square)
![Paper](https://img.shields.io/badge/Platform-Paper-222222?style=flat-square)
![Java](https://img.shields.io/badge/Java-25-orange?style=flat-square)
![Version](https://img.shields.io/badge/Version-1.1.0--beta1-blue?style=flat-square)
![Status](https://img.shields.io/badge/Status-Experimental-yellow?style=flat-square)

*Collect mob trophies, configure drop rates, and make every kill count.*

</div>

> [!WARNING]
> **Beta release.** SkullDrops is under development. Some mob variants may not have a matching texture, and Minecraft-Heads API access depends on the provider's licensing and application status.

## ✨ Features

- Configurable mob and player head drop chances.
- Support for mob variants and baby mobs.
- Configurable Looting bonus.
- Special drops for Creaking Hearts and Copper Golem statues.
- Mob head textures from **Minecraft-Heads API v2**, stored in a local cache.
- Private API license key stored separately from the public configuration.
- OP-only test mode for testing head drops.

## 📋 Requirements

| Component | Requirement |
| --- | --- |
| Minecraft | 26.3 |
| Server | Paper |
| Java | 25 |
| Build tool | Gradle |

## 🚀 Build

1. Clone or download the repository.
2. Open the project in IntelliJ IDEA and select **JDK 25** for the project and Gradle.
3. Run:

```bash
./gradlew clean build
```

On Windows, use `gradlew.bat clean build`. The compiled JAR is located in `build/libs/`.

## 📦 Installation

1. Stop the Paper server and place the JAR in `plugins/`.
2. Start the server to generate the configuration files.
3. Adjust `plugins/SkullDrops/config.yml` to your preferences.
4. If using a Minecraft-Heads license key, add it to `plugins/SkullDrops/secrets.yml`:

```yaml
minecraft-heads:
  api-key: "YOUR_PRIVATE_API_KEY"
```

5. Restart the server and check `/sd status`.

> [!IMPORTANT]
> Never publish `secrets.yml`, your API key, or the downloaded texture cache. Each server owner must use their own credentials. SkullDrops does not bundle Minecraft-Heads textures.

## ⚙️ Configuration

Head drop probabilities are configured as percentages from **0 to 100** in `config.yml`. You can adjust mob and player drop rates, variants, baby mobs, Looting, and special drops.

Mob head textures are resolved through [Minecraft-Heads.com](https://minecraft-heads.com/) and cached locally. Heads without a matching texture may not drop.

## 🎮 Commands

| Command | Description |
| --- | --- |
| `/sd status` | Show plugin and texture synchronization status |
| `/sd reload` | Reload plugin configuration |
| `/sd test on` | Enable temporary 100% test drop chances |
| `/sd test off` | Disable test mode |
| `/sd test status` | Show test mode status |
| `/sd texture missing` | List missing texture mappings |
| `/sd texture search <name>` | Search locally downloaded texture entries |
| `/sd texture export` | Export downloaded texture entries to CSV |

`/skulldrops` is an alias for `/sd`. Test mode is restricted to operators or the console and resets on server restart. Missing textures and other drop restrictions may still apply.

## ⚠️ Beta limitations

- Texture coverage for all mobs and variants is not yet complete.
- Minecraft-Heads may return limited data if the application is inactive or API access is restricted.
- API synchronization and some special drops are still being tested.

## 🤝 Contributing

Bug reports and pull requests are welcome. Please include your Paper version, relevant logs, and steps to reproduce the issue.

## 🙏 Credits

Mob head textures are provided by [Minecraft-Heads.com](https://minecraft-heads.com/) and are subject to their API terms and attribution requirements.

[![Minecraft-Heads](docs/images/minecraft-heads_fullbanner_468x60.png)](https://minecraft-heads.com/)

## 📄 License

SkullDrops is licensed under the GNU General Public License v3.0.

See [LICENSE](LICENSE) for details.

Minecraft-Heads API and textures are subject to their own
terms of use and licensing conditions.

---

<div align="center">

Made by **JaraSG2** · **SkullDrops** · Version **1.1.0-beta1**

</div>
