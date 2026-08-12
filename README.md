# JEI Crafter

A pure-client mod that turns JEI bookmarks into a one-click crafting pipeline.

## Features

- **Auto-craft bookmarked recipes** — hold **Z** and click a recipe bookmark to auto-detect
  materials, recursively craft missing bookmarked materials, and complete the crafting.
- **Workstation recipes (non-crafting)** — recipes that need a workstation (smelting, blasting,
  smoking, brewing, …) are executed by automatically opening the **nearest matching workstation**
  block in the loaded world, transferring the recipe's required inputs (and fuel for furnace-family
  menus) into it, and extracting the output when it finishes. Both the "open workstation" and
  "transfer items" steps can be disabled in the config.
- **Highlight recipe workstations** — on a non-crafting JEI recipe page, press **H** to highlight
  every workstation block for the current recipe across the loaded world.

## Configuration

A `jeicrafter.json` config file (in the platform config directory) controls the workstation
features:

| Key | Default | Meaning |
| --- | --- | --- |
| `enableWorkstationHighlight` | `true` | Enable the recipe-workstation highlight (H key). |
| `autoOpenWorkstation` | `true` | Auto-craft opens the nearest matching workstation GUI. |
| `autoTransferItems` | `true` | Auto-craft transfers the recipe's required items into the workstation. |
| `autoCloseWorkstation` | `true` | Auto-craft closes the workstation GUI after extracting the output. |
| `workstationMaxDistance` | `64` | Max distance (blocks) to search for a workstation to open. |

## API

Third-party mods can reuse the bookmark dependency resolver and provide actions for their own JEI
recipe types. See [the bookmark action API](docs/action-api.md).

## License

This project is licensed under the **GNU Lesser General Public License v3.0 (LGPL-3.0-only)**. See the
[LICENSE](LICENSE) file for details.

## Thanks

The workstation highlight renderer is adapted from
[clientcommands](https://github.com/Earthcomputer/clientcommands) v2.8.2
(Earthcomputer and contributors). Thanks for the reference implementation of block highlighting
across the loaded world.
