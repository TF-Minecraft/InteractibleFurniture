# InteractibleFurniture

> Furniture players can use, arrange, and fill in TF-Minecraft.

InteractibleFurniture makes custom furniture part of everyday interaction. A shelf can display individual tools, a pan can hold ingredients, and a larger piece can hold smaller furniture. Players interact with the objects and their visible contents directly in the world.

The plugin supplies the shared furniture behaviour used by other TF-Minecraft experiences, including cooking and magic. Those plugins can build their own activities around the same placement, attachment, and item-handling system.

## Features

- **Placeable custom furniture** — bring item models into the world with placement rules suited to each piece, including floor and wall placement.
- **Visible item slots** — put supported items into specific positions and take them back out, with their contents displayed on the furniture.
- **Furniture within furniture** — attach compatible pieces to a parent object and interact with their own slots.
- **Movable pieces** — rotate, pick up, or carry furniture where the piece supports those actions.
- **Persistent arrangements** — save placed furniture and its contents so the arrangement can be restored.
- **Tactile feedback** — pair placement and removal with sounds and tailored visual positioning.

## Documentation

[Project documentation](https://github.com/TF-Minecraft/Docs/blob/main/projects/InteractibleFurniture/README.md)

Technical documentation is maintained in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs).

## Tests

Install the pinned shared plugin dependencies, download the private build
inputs, then run the build with Java 21:

```sh
python3 path/to/TLibs/tools/install-plugins.py --pom pom.xml --mode pinned
read -rsp 'ServerAssets token: ' GH_TOKEN && echo && export GH_TOKEN
bash .github/scripts/prepare-release.sh
mvn clean verify
```

Point the installer at your TLibs checkout. The token needs Contents read access
to TF-Minecraft/ServerAssets; reading it with a prompt keeps it out of shell
history. CI supplies it from `DEPS_TOKEN`.

Tests use JUnit, Mockito, and MockBukkit and run without a live Minecraft server.

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and bundled material
retain their own licenses.
