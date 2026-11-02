"""W5.4's one-line breaks: the patcher (afterPatch, requiredPlanets, @NoPatch, values), the asset section (order and
embedding), the reader, the map loader's patched content and section, the world stream's patch section and the asset
streams, each applied alone to a clean checkout and run against the patch tests: DataPatcherTest, the extractor's
vanilla patcher gate (ContentGatesTest), MapLoaderTest, and the MapPatches and PatchedMaps oracle tests. A break must
fail at least one of them. A clean run comes first, and must pass.

Run on a VM, from the Gradle root (scripts/vm-gradle.sh --shell 'python3 scripts/breaks/w5.4-map-patches.py'). The
results, as committed, are in w5.4-map-patches.log.
"""
import os

import runner

os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))

PATCH = 'content/src/main/java/com/xpdustry/shard/content/patch/'
PATCHER = PATCH + 'DataPatcher.java'
ASSETS = PATCH + 'DataAssets.java'
JSON = PATCH + 'PatchJson.java'
LOADER = 'compat/src/main/java/com/xpdustry/shard/compat/v160/msav/MapLoader.java'
WORLD = 'compat/src/main/java/com/xpdustry/shard/compat/v160/world/'

FIXTURES = ['DataPatcherTest', 'MapLoaderTest', 'ContentGatesTest', 'MapPatchesOracleTest', 'PatchedMapsOracleTest']

# (name, file, the original text (exactly once in the file), the break)
BREAKS = [
    ('clean', None, None, None),
    ('patcher: a unit keeps its path type', PATCHER,
     'if(type == ContentType.UNIT) unitAfterPatch(b);', 'if(false) unitAfterPatch(b);'),
    ('patcher: requiredPlanets ignored', PATCHER,
     'if(!requiredPlanets(required).contains(planet)) continue;', 'if(false) continue;'),
    ('patcher: @NoPatch fields edited', PATCHER,
     'if(spec.noPatch()){\n                    warn("field \'" + at.describe(base) + "." + field + "\' cannot be edited");\n                    return;',
     'if(false){\n                    warn("field \'" + at.describe(base) + "." + field + "\' cannot be edited");\n                    return;'),
    ('patcher: a float a little off', PATCHER,
     '(Object)(float)real(at, json);', '(Object)(float)(real(at, json) * 1.0001);'),
    ('patcher: a crafter always outputs liquid', PATCHER,
     'b.set("outputsLiquid", b.get("outputLiquids") != null);', 'b.set("outputsLiquid", true);'),
    ('reader: every number a double', JSON,
     'return Long.parseLong(number);', 'return Double.parseDouble(number);'),
    ('section: assets in file order', ASSETS,
     'out.sort(Comparator.comparingInt(a -> a.type().ordinal()));', '// unsorted'),
    ('section: external assets always embedded', ASSETS,
     'boolean embed = forceEmbed || asset.type().embedded() || hash == null;', 'boolean embed = true;'),
    ('loader: the world plays the template content', LOADER,
     'content = file.content();', '// content = file.content();'),
    ('loader: the section is not kept', LOADER,
     'state.patches = file.section();', 'state.patches = null;'),
    ('stream: the epoch patch always', WORLD + 'WorldProjection.java',
     'state.patches != null ? state.patches : patches', 'patches'),
    ('assets: a repeated request sent twice', WORLD + 'AssetStreams.java',
     'if(id >= all.size() || id < 0 || seen.get(id)) continue;', 'if(id >= all.size() || id < 0) continue;'),
    ('assets: requirements without hashes', WORLD + 'AssetStreams.java',
     'out.write(hash);', 'out.write(new byte[32]);'),
]

runner.run(BREAKS, FIXTURES,
           [':content:test', ':compat:test', '--tests', '*.MapLoaderTest', ':tools:content-extract:test', '--tests', '*.ContentGatesTest',
            ':compat:oracleTest'],
           ['content/build/test-results/test', 'compat/build/test-results/test', 'tools/content-extract/build/test-results/test',
            'compat/build/test-results/oracleTest'])
