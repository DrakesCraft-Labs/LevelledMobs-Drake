> ### Fork de DrakesCraft — `LevelledMobs-Drake`
>
> Fork de [`ArcanePlugins/LevelledMobs`](https://github.com/ArcanePlugins/LevelledMobs)
> mantenido por [DrakesCraft-Labs](https://github.com/DrakesCraft-Labs) para
> `mc.drakescraft.cl`. Solo se aplican parches quirurgicos sobre la version
> publicada; el resto del codigo sigue siendo el del upstream.
>
> | Version del fork | Rama | Divergencia respecto al upstream |
> |---|---|---|
> | `4.5.3.2 b159-Drake.1` | `fix/cme-modal-list-339` | `ConcurrentModificationException` al recorrer `applicableGroups` y `mobExternalTypes` del `LivingEntityWrapper` desde el hilo asincrono de `MobsQueueManager` ([upstream #545](https://github.com/ArcanePlugins/LevelledMobs/issues/545), [#538](https://github.com/ArcanePlugins/LevelledMobs/issues/538)). Los conjuntos pasan a `@Volatile` y se sustituyen en vez de vaciarse in situ, y los dos recorridos usan una instantanea. Ademas se fija `worldguard-bukkit` a `7.0.17`, la version que corre el servidor, porque el `7.1.0-SNAPSHOT` ya se publica para Java 25 y Gradle lo rechaza contra la cadena de herramientas Java 21 del modulo. |
> | `4.5.3.2 b159-Drake.2` | `fix/applicable-rules-cme-441` | `MobsQueueManager` reiniciaba sus workers de cola por acumulacion y dejaba vivos los reemplazados, multiplicando los hilos que recorren las reglas. Ahora el worker sustituido se retira y no se relanza por backlog. |
> | `4.5.3.2 b159-Drake.3` | `fix/applicable-rules-cme-441` | `applicableRules` del `LivingEntityWrapper` se vaciaba in situ mientras `RulesManager` lo recorria fuera del hilo principal: `ConcurrentModificationException` superviviente al parche de Drake.1. La lista se sustituye en vez de vaciarse. |
> | `4.5.3.2 b159-Drake.4` | `fix/applicable-rules-cme-441` | `RulesManager.isRuleApplicableEntity` tomaba `livingEntity.scoreboardTags`, que en CraftBukkit **no es una copia** sino el `Entity.tags` real (un `SizeLimitedSet` de Paper sobre un `ObjectOpenHashSet` sin sincronizar): lo mutaba con `add("(none)")` y lo recorria desde el hilo asincrono de nametags. Provocaba la NPE de fastutil al evaluar reglas y, peor, hacia fallar `Entity.saveWithoutId`, con lo que la entidad se perdia al descargar el chunk. Ahora se copia sin tocar la entidad y el centinela solo existe en la copia. |
>
> Compilacion: `JAVA_HOME=<jdk21> ./gradlew build -x test` — el jar sale en
> `levelledmobs-plugin/build/libs/`.

<td style="text-align: center;">

<img src="https://i.ibb.co/ySgMPd0/Levelled-Mobs-Banner-v2-0.png" width="600" height="300" alt="LevelledMobs Banner" />

# LevelledMobs 4

*An ArcanePlugins Resource • by PenalBuffalo, UltimaOath, and lokka30*

<a href="https://discord.gg/arcaneplugins-752310043214479462">
<img src="https://img.shields.io/badge/Chat%20%2F%20Support-on%20Discord-skyblue?style=for-the-badge&logo=discord&logoColor=white" alt="Support available on Discord">
</a>
<a href="https://www.spigotmc.org/threads/levelledmobs.412953/">
<img src="https://img.shields.io/badge/Chat%20%2F%20Support-Spigot%20PM-skyblue?style=for-the-badge&logo=googlemessages" alt="Support available through Spigot PMs">
</a>

<br />

<a href="https://arcaneplugins.gitbook.io/levelledmobs-the-ultimate-mob-levelling-solution">
<img src="https://img.shields.io/badge/Documentation-on%20Wiki-skyblue?style=for-the-badge&logo=github" alt="Documentation available on the Wiki">
</a>

<a href="https://arcaneplugins.gitbook.io/levelledmobs-the-ultimate-mob-levelling-solution/credits-and-misc/developers-and-contributors">
    <img src="https://img.shields.io/badge/Contributors-View%20Credits-skyblue?style=for-the-badge" alt="Contributors listed in Credits Page">
</a>

<br /><hr />

## Learn More at [✈️ HangarMC](https://hangar.papermc.io/ArcanePlugins/LevelledMobs) or [🚰 SpigotMC](https://www.spigotmc.org/resources/levelledmobs.74304/)

### Also see: [Errors and Running LM](https://arcaneplugins.gitbook.io/levelledmobs-the-ultimate-mob-levelling-solution/levelledmobs-v4.0/errors-and-running-lm) • [Installation Instructions](https://arcaneplugins.gitbook.io/levelledmobs-the-ultimate-mob-levelling-solution/levelledmobs-v4.0/installation)

</td>

<br /><hr />

## Embedded Projects

We are grateful to the  authors and contributors of these projects,
as they make the current version of LevelledMobs possible.

Remember to give these projects a star. :)

- [CommandAPI](https://github.com/JorelAli/CommandAPI) (JorelAli)
- [Configurate](https://github.com/SpongePowered/Configurate/) (SpongePowered Team)
- [Crunch](https://github.com/Redempt/Crunch) (Redempt)
- [MineDown](https://github.com/Phoenix616/MineDown) (Phoenix616)
- [MorePersistentDataTypes](https://github.com/JEFF-Media-GbR/MorePersistentDataTypes) (JEFF-Media-GbR)
- [Item-NBT-API](https://github.com/tr7zw/Item-NBT-API) (tr7zw)
- [Paper-API](https://github.com/PaperMC/Paper) (PaperMC Team)
- [PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI) (PlaceholderAPI Team)
- [Spigot-API](https://www.spigotmc.org/) (SpigotMC Team)

## License

[![GPLv3 or Later](https://www.gnu.org/graphics/gplv3-with-text-84x42.png)](https://www.gnu.org/licenses/gpl-3.0.html)
> Copyright © 2020-2024 lokka30
>
> Copyright © 2020-2024 PenalBuffalo (aka stumper66)
>
> Copyright © 2020-2024 UltimaOath (aka Oathkeeper)
>
> Copyright © 2020-2024 LevelledMobs Contributors
>
> This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
>
> This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
>
> You should have received a copy of the GNU General Public License along with this program.  If not, see <https://www.gnu.org/licenses/>.

**[View License Document: `LICENSE.md`](LICENSE.md)**

<br /><hr />

<td style="text-align: center;">

## Learn More at [✈️ HangarMC](https://hangar.papermc.io/ArcanePlugins/LevelledMobs) or [🚰 SpigotMC](https://www.spigotmc.org/resources/levelledmobs.74304/)

### Also see: [Server Compatibility](https://arcaneplugins.gitbook.io/levelledmobs-the-ultimate-mob-levelling-solution/levelledmobs-v4.0/installation#are-you-running-a-compatible-server-software) • [Installation Instructions](https://arcaneplugins.gitbook.io/levelledmobs-the-ultimate-mob-levelling-solution/levelledmobs-v4.0/installation)

</td>
