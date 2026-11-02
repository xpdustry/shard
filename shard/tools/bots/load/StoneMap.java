// Writes the load gate's stone map: W1.6's stone world (size x size stone, nothing built, no core) as an .msav, so
// the real server hosts it through the same path as any map. load.sh runs it with the server's install:
//   java -cp 'server/build/install/shard/lib/*' tools/bots/load/StoneMap.java <file> [size, default 50]

import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.world.*;

import java.nio.file.*;

void main(String[] args) throws Exception{
    int size = args.length > 1 ? Integer.parseInt(args[1]) : 50;
    Epoch epoch = Epoch.vanilla();
    WorldConfig template = HostedSystems.template(epoch, 0, 256);
    var world = new World(new WorldConfig(template.content(), size, size, 0, template.systems(), template.kinds(), template.inboxCapacity(),
        template.journal()));
    int stone = epoch.block("stone").id();
    for(int pos = 0; pos < size * size; pos++) world.tiles().floor(pos, stone);

    var state = new MapState();
    state.tags.put("name", "Stone");
    state.tags.put("author", "shard");
    state.tags.put("description", "W1.6's stone world: " + size + "x" + size + " stone, nothing built.");
    int team = world.part(WorldRules.class).rules.defaultTeam;
    WorldStreamSource source = new WorldProjection(epoch, BuildingCodecs.builtIn()).capture(world, state, PlayerState.fresh(0, "", team));
    Files.write(Path.of(args[0]), MsavWriter.write(MsavWriter.meta(source, state.tags, team, true, 0L), source));
}
