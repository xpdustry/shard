package com.xpdustry.shard.compat.v160.filters;

/**
 * Vanilla's {@code GenerateFilter}: one pass over a loading map's tiles. A filter is its options as data; the editor
 * UI ({@code options()}, {@code draw}) is not ported.
 *
 * <p>Most filters map one tile at a time ({@link #apply(GenerateInput)}) and let {@link #apply(FilterGrid, GenerateInput)}
 * walk the tiles in index order, as vanilla's: a buffered filter reads every tile before writing any, the others
 * write each tile as they go, so later tiles see earlier writes. The rest ({@link #post()} filters) override the
 * whole pass.
 */
public sealed interface GenerateFilter permits BlendFilter, ClearFilter, CoreSpawnFilter, DistortFilter,
    EnemySpawnFilter, LogicFilter, MedianFilter, MirrorFilter, NoiseFilter, OreFilter, OreMedianFilter, RandomItemFilter,
    RiverNoiseFilter, ScatterFilter, SpawnPathFilter, TerrainFilter{

    /** {@code isBuffered()}: reads its neighbours, so needs a read buffer. */
    default boolean buffered(){
        return false;
    }

    /** {@code isPost()}: whole-map only, applied once on load. */
    default boolean post(){
        return false;
    }

    /** {@code apply(GenerateInput)}: turns the input's tile into the output, in place. */
    default void apply(GenerateInput in){
    }

    /** {@code apply(Tiles, GenerateInput)} (GenerateFilter.java:19). */
    default void apply(FilterGrid grid, GenerateInput in){
        int size = grid.size();
        if(buffered()){
            // vanilla packs block, floor and overlay into a long per tile
            int[] buffer = new int[size * 3];
            for(int i = 0; i < size; i++){
                in.set(i);
                apply(in);
                buffer[i * 3] = in.block;
                buffer[i * 3 + 1] = in.floor;
                buffer[i * 3 + 2] = in.overlay;
            }
            for(int i = 0; i < size; i++){
                // vanilla reads the ids back with content.block (FilterGrid.byId)
                write(grid, i, grid.byId(buffer[i * 3 + 1]), grid.byId(buffer[i * 3 + 2]), grid.byId(buffer[i * 3]));
            }
        }else{
            for(int i = 0; i < size; i++){
                in.set(i);
                apply(in);
                write(grid, i, in.floor, in.overlay, in.block);
                grid.data(i, in.data, in.floorData, in.overlayData, in.extraData);
            }
        }
    }

    /**
     * The write of one tile: a floor output sets the floor and the overlay, dropping an ore that needs a surface the
     * floor lacks; a block output replaces only a block nobody built, with one nobody builds.
     */
    private static void write(FilterGrid grid, int pos, int floor, int overlay, int block){
        if(grid.isFloor(floor)){
            grid.setFloor(pos, floor);
            grid.setOverlay(pos, !grid.hasSurface(floor) && needsSurface(grid, overlay) && grid.isOre(overlay) ? 0 : overlay);
        }
        if(!grid.isSynthetic(grid.block(pos)) && !grid.isSynthetic(block)){
            grid.setBlock(pos, block);
        }
    }

    /** {@code overlay.asFloor().needsSurface}, whose cast fails vanilla's load when the overlay isn't a floor. */
    private static boolean needsSurface(FilterGrid grid, int overlay){
        grid.requireFloor(overlay, "overlay");
        return grid.needsSurface(overlay);
    }
}
