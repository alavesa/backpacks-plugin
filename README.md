# Backpacks (Paper 26.2)

Upgradable, wearable, placeable backpacks with Blockbench models. Built against `paper-api 26.2.build.135`,
Java 21, Maven. Jar: `target/Backpacks-1.0.0.jar`. Plugin folder: `plugins/backpacks/`.

## Players

| Action | Result |
|---|---|
| Right-click with the backpack in hand | opens its storage |
| Sneak + right-click air | opens the upgrade screen |
| Sneak + right-click a block | places the backpack (sneak + right-click a container with a Deposit/Restock upgrade moves items instead) |
| Right-click the backpack in your inventory (empty cursor) | opens its storage; shift + right-click opens the upgrades |
| Put it in the chest slot | it is rendered on your back |
| Placed backpack: right-click | opens it (shared like a chest) |
| Placed backpack: sneak + right-click with an empty hand, or left-click | picks it up (contents stay inside) |

Placed backpacks accept hoppers (into / out of) and droppers pointed at them. Contents live server-side under
the backpack's id (`plugins/backpacks/data/<uuid>.yml`), so the item stays small and nested backpacks are safe.

## Tiers

Leather 9/1, Copper 18/2, Iron 27/3, Gold 36/4, Diamond 45/5, Netherite 54/6 (storage slots / upgrade slots) -
all editable in `config.yml` (`tiers.<id>.slots`, `upgrade-slots`, `model`, `material`, `name`, `color`) or with
`/backpack type create|edit|remove|list`.

Recipes (`recipes.yml`): 8 x material + chest for each tier, and 8 x material + the previous backpack to upgrade
in place (contents and upgrades carry over). Upgrade recipes start from an *Upgrade Base* (sticks, leather, iron).

## Upgrades

pickup / advanced pickup, magnet / advanced magnet, feeding / advanced feeding, void, refill, compacting,
inception (backpacks inside backpacks), everlasting (dropped backpack never despawns, burns or falls into the
void), deposit, restock. Filter slots per upgrade: `config.yml` -> `upgrades.<id>.filter-slots`. Right-click an
installed upgrade in the upgrade screen to set its filter (click items from your inventory onto the slots; they
are not consumed). Modes: whitelist / blacklist (deposit & restock also "match existing").

Upgrades work while the backpack is worn and, by default, anywhere in the inventory (`upgrades.active-in-inventory`).

## Models and the resource pack

Put `.bbmodel` files into `plugins/backpacks/backpack_models/` (six example models are extracted on first start).
The plugin reads them (cubes, groups with rotation, per-face or box UV, embedded or sibling-file textures) and
writes `plugins/backpacks/resourcepack/` plus `Backpacks-resourcepack.zip` (sha1 next to it). Players install the
pack manually - it is **not** sent to clients. A tier's `model` is the file name without extension; the item gets
`item_model = backpacks:<model>`. Rebuild with `/backpack pack`; `pack-report.txt` lists what was exported and why
something was not. In Blockbench use the Java Block/Item format and set the Display transforms there for the
in-hand / GUI look; the worn and placed displays use the model 1:1 (16 px = 1 block) with its bottom-centre on the
entity and are scaled by `worn.scale` / `placed.scale`.

## Commands (`/backpack`, aliases `/bp`, `/backpacks`)

`open [player]`, `upgrades`, `info`, `models` for everyone (`backpacks.use`);
`give <player> <tier> [n]`, `giveupgrade <player> <upgrade|upgrade_base> [n]`, `settier <player> <tier>`,
`type create|edit|remove|list`, `pack`, `reload` for `backpacks.admin`.

## Loot

`loot.chests`: chance of a backpack / an upgrade in every `chests/*` loot table (weighted by tier).
`loot.mobs`: listed mobs spawn wearing a backpack now and then, optionally with loot from a loot table inside.

## Build / test

```
mvn package          # 6 unit tests (model pipeline), jar in target/
```
Boot-tested on Paper 26.2 build 135 (0 exceptions, 25 recipes, 6 models in the pack).
Not yet verified with a real client: in-hand/GUI look, worn offset (`worn.translation`, `worn.yaw-offset`),
placed model orientation (`placed.yaw-offset`), click feel on placed backpacks.

## Notes

- Base item is `PAPER` (configurable per tier); without the pack players see named paper.
- Placing does not go through other plugins' block protection (WorldGuard etc.); permission `backpacks.place`.
- Copying a backpack item in creative copies its id, so both copies share one storage (like a linked chest).
