//! End-to-end force-loaded chunk tests against REAL fixtures copied from
//! OrzMCBackup: the legacy `data/chunks.dat` (Forge Forced LongArray) and the
//! 26.1+ `data/minecraft/chunk_tickets.dat` (minecraft:forced tickets).

use rust_thanos::mca::reader::McaReader;
use rust_thanos::world::ProgressMode;
use std::fs;
use std::path::Path;

/// Opens the output region and returns its sorted chunk region-indices.
fn output_indices(out: &Path) -> Vec<u32> {
    let mut r = McaReader::open(
        out.join("region")
            .join("r.0.0.mca")
            .to_string_lossy()
            .as_ref(),
    )
    .unwrap();
    let mut v: Vec<u32> = r
        .entries()
        .unwrap()
        .iter_mut()
        .map(|e| e.region_index())
        .collect();
    v.sort_unstable();
    v
}

/// Maps global chunk coords inside region r.0.0 (x,z ∈ 0..32) to their region
/// index (z*32+x), sorted.
fn indices_for_coords(coords: &[(i32, i32)]) -> Vec<u32> {
    let mut v: Vec<u32> = coords
        .iter()
        .filter(|(x, z)| (0..32).contains(x) && (0..32).contains(z))
        .map(|(x, z)| (z * 32 + x) as u32)
        .collect();
    v.sort_unstable();
    v.dedup();
    v
}

/// `data/chunks.dat` (tickets structure) keeps the force-loaded chunk even at a very
/// high threshold. The real fixture marks global chunk (1,1) → region index 33.
#[test]
fn legacy_chunks_dat_keeps_forced_chunk() {
    let base = std::env::temp_dir().join(format!("rt-forced-legacy-{}", uuid::Uuid::new_v4()));
    fs::create_dir_all(&base).unwrap();
    let world = base.join("world");
    fs::create_dir_all(world.join("region")).unwrap();
    fs::create_dir_all(world.join("data")).unwrap();
    fs::copy(
        "tests/Fixtures/world/region/r.0.0.mca",
        world.join("region").join("r.0.0.mca"),
    )
    .unwrap();
    fs::copy(
        "tests/Fixtures/world/data/chunks.dat",
        world.join("data").join("chunks.dat"),
    )
    .unwrap();

    let forced = rust_thanos::world::parse_force_loaded(&world);
    assert_eq!(forced, vec![(1, 1)]);

    let out = base.join("out");
    rust_thanos::world::run(
        world.clone(),
        Some(out.clone()),
        1_000_000,
        false,
        ProgressMode::Off,
    )
    .unwrap();
    assert_eq!(output_indices(&out), vec![33]); // global (1,1)
}

/// Modern `data/minecraft/chunk_tickets.dat` (26.1+) keeps the force-loaded
/// chunks. The real fixture marks a 2x2 block (0,0),(0,1),(1,0),(1,1).
#[test]
fn chunk_tickets_dat_keeps_forced_chunks() {
    let base = std::env::temp_dir().join(format!("rt-forced-tickets-{}", uuid::Uuid::new_v4()));
    fs::create_dir_all(&base).unwrap();
    // Copy the whole 26.1 overworld dimension: region + data (incl. both the
    // modern chunk_tickets.dat and the legacy chunks.dat).
    let dim = base.join("dim");
    fs::create_dir_all(&dim).unwrap();
    let mut opts = fs_extra::dir::CopyOptions::new();
    opts.content_only = true;
    fs_extra::dir::copy(
        "tests/Fixtures/world-26-1/dimensions/minecraft/overworld",
        &dim,
        &opts,
    )
    .unwrap();

    // First file in priority order wins: chunk_tickets.dat (not chunks.dat).
    let forced = rust_thanos::world::parse_force_loaded(&dim);
    assert_eq!(forced, vec![(0, 0), (0, 1), (1, 0), (1, 1)]);
    let expected = indices_for_coords(&forced);

    let out = base.join("out");
    rust_thanos::world::run(
        dim.clone(),
        Some(out.clone()),
        1_000_000,
        false,
        ProgressMode::Off,
    )
    .unwrap();
    assert_eq!(output_indices(&out), expected);
}

/// Writes a legacy-format `data/chunks.dat` whose `data.Forced` LongArray uses the vanilla
/// `ChunkPos::to_long(x, z)` packing (x in the low 32 bits, z in the high 32 bits), one long
/// per chunk — exactly what `ForcedChunksSavedData` stores.
fn write_legacy_forced_dat(dir: &Path, coords: &[(i32, i32)]) {
    use std::collections::HashMap;
    use std::io::Write;

    let forced: Vec<i64> = coords
        .iter()
        .map(|(x, z)| ((*z as i64) << 32) | (*x as i64 & 0xFFFF_FFFF))
        .collect();
    let mut data = HashMap::new();
    data.insert(
        "Forced".to_string(),
        fastnbt::Value::LongArray(fastnbt::LongArray::new(forced)),
    );
    let mut root = HashMap::new();
    root.insert("data".to_string(), fastnbt::Value::Compound(data));
    let nbt = fastnbt::to_bytes(&fastnbt::Value::Compound(root)).unwrap();

    let mut gz = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
    gz.write_all(&nbt).unwrap();
    let payload = gz.finish().unwrap();

    fs::create_dir_all(dir.join("data")).unwrap();
    fs::write(dir.join("data").join("chunks.dat"), payload).unwrap();
}

/// Legacy `data.Forced` regression: each packed long is exactly one chunk (x = low 32 bits,
/// z = high 32 bits). An odd number of entries must not drop or merge anything — the old
/// "pair two longs per chunk" reader returned half as many chunks with scrambled coordinates,
/// so real force-loaded chunks were pruned and unrelated ones were kept.
#[test]
fn legacy_forced_long_array_maps_one_chunk_per_long() {
    let base = std::env::temp_dir().join(format!("rt-forced-forced-{}", uuid::Uuid::new_v4()));
    fs::create_dir_all(&base).unwrap();
    let world = base.join("world");
    fs::create_dir_all(world.join("region")).unwrap();
    fs::copy(
        "tests/Fixtures/world/region/r.0.0.mca",
        world.join("region").join("r.0.0.mca"),
    )
    .unwrap();

    // Odd count on purpose: (3,0) -> region index 3, (1,1) -> 33, (2,1) -> 34.
    let expected = vec![(3, 0), (1, 1), (2, 1)];
    write_legacy_forced_dat(&world, &expected);

    assert_eq!(rust_thanos::world::parse_force_loaded(&world), expected);

    let out = base.join("out");
    rust_thanos::world::run(
        world.clone(),
        Some(out.clone()),
        1_000_000,
        false,
        ProgressMode::Off,
    )
    .unwrap();
    assert_eq!(output_indices(&out), indices_for_coords(&expected));
}
