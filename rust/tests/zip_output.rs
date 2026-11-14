//! `--zip-output` end-to-end (guards the zip crate major bump: 0.6 -> 9.x).
//!
//! The archive must be written **next to** the output directory (never inside the tree being
//! walked), entries must use `/` separators (ZIP spec; Windows paths would break unzip), file
//! contents must survive the round trip, and the output directory must be removed only after
//! the archive was written.

use rust_thanos::world::{
    DefaultOptimizer, OptimizerEngine, OptimizerRequestBuilder, ProgressMode,
};
use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};

/// Copies the real fixture world to a fresh temp dir (676 chunks; any threshold keeps some).
fn fixture_world() -> PathBuf {
    let base = std::env::temp_dir().join(format!("rt-zip-{}", uuid::Uuid::new_v4()));
    let world = base.join("world");
    fs::create_dir_all(&world).unwrap();
    let mut opts = fs_extra::dir::CopyOptions::new();
    opts.content_only = true;
    fs_extra::dir::copy("tests/Fixtures/world", &world, &opts).unwrap();
    world
}

fn only_zip_in(dir: &Path) -> Option<PathBuf> {
    let mut zips: Vec<PathBuf> = fs::read_dir(dir)
        .ok()?
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.extension().and_then(|e| e.to_str()) == Some("zip"))
        .collect();
    zips.sort();
    zips.into_iter().next()
}

#[test]
fn zip_output_writes_sibling_archive_with_slash_separated_entries() {
    let world = fixture_world();
    let out = world.parent().unwrap().join("out");

    let request = OptimizerRequestBuilder::new(world.clone())
        .output(Some(out.clone()))
        .inhabited_threshold_ticks(Some(0))
        .progress_mode(ProgressMode::Off)
        .zip_output(true)
        .build();
    let report = DefaultOptimizer.run(&request).expect("optimize run");
    assert!(
        report.errors.iter().all(|e| e.kind.as_str() != "Compress"),
        "zip must not fail: {:?}",
        report.errors
    );

    assert!(
        !out.exists(),
        "output directory must be removed after a successful zip"
    );
    let zip_path =
        only_zip_in(world.parent().unwrap()).expect("archive must be written next to the output");
    assert_ne!(
        zip_path.parent(),
        Some(out.as_path()),
        "archive must not live inside the output"
    );

    let file = fs::File::open(&zip_path).unwrap();
    let mut archive = zip::ZipArchive::new(file).unwrap();
    let names: Vec<String> = (0..archive.len())
        .map(|i| archive.by_index(i).unwrap().name().unwrap().to_string())
        .collect();
    assert!(
        names.iter().any(|n| n == "level.dat"),
        "entries: {:?}",
        names
    );
    assert!(
        names
            .iter()
            .any(|n| n.starts_with("region/") && n.ends_with(".mca")),
        "region entries must use '/' separators: {:?}",
        names
            .iter()
            .filter(|n| n.contains("region"))
            .collect::<Vec<_>>()
    );
    assert!(
        !names.iter().any(|n| n.contains("session.lock")),
        "session.lock must never be archived: {:?}",
        names
    );

    let mut level = Vec::new();
    archive
        .by_name("level.dat")
        .unwrap()
        .read_to_end(&mut level)
        .unwrap();
    assert_eq!(
        level,
        fs::read(world.join("level.dat")).unwrap(),
        "content must round-trip"
    );
}
