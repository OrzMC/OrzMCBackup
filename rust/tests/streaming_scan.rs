//! Streaming chunk access (WOS-10.2), adopted from the upstream PHP implementation which reads
//! chunks as a stream instead of materialising the whole decompressed payload.
//!
//! Two things are pinned here:
//! 1. the streaming view yields exactly the same bytes as the buffered view (all compressions);
//! 2. the structural scanner reads the same value from a stream and reports structural damage as
//!    `Malformed` (so the engine keeps the chunk and records an error, WOS-6.6).

use rust_thanos::mca::reader::McaReader;
use rust_thanos::patterns::inhabited::{find_inhabited_time, scan, ScanResult};

const FIXTURE_REGION: &str = "tests/Fixtures/world/region/r.0.0.mca";

/// Every entry of the real fixture must stream exactly what the buffered API returns.
#[test]
fn streaming_view_matches_buffered_view_on_real_world() {
    let mut reader = McaReader::open(FIXTURE_REGION).expect("open fixture");
    let mut checked = 0;
    for idx in 0..1024u32 {
        let Some(mut entry) = reader.get(idx as usize).expect("read slot") else {
            continue;
        };
        if entry.is_external().unwrap_or(false) {
            continue;
        }
        let buffered = match entry.all_data_uncompressed() {
            Ok(bytes) => bytes,
            Err(_) => continue, // damaged/unreadable chunk: covered by the engine's safe-keep path
        };
        let mut stream = entry.open_data_stream().expect("stream");
        let mut streamed = Vec::new();
        std::io::Read::read_to_end(&mut stream, &mut streamed).expect("read stream");
        assert_eq!(
            streamed.len(),
            buffered.len(),
            "slot {idx}: streaming length differs from buffered length"
        );
        assert_eq!(streamed, buffered, "slot {idx}: streaming bytes differ");
        checked += 1;
    }
    assert!(
        checked > 600,
        "expected to check hundreds of real chunks, got {checked}"
    );
}

fn root(entries: &[Vec<u8>]) -> Vec<u8> {
    let mut out = vec![0x0A, 0x00, 0x00];
    for e in entries {
        out.extend_from_slice(e);
    }
    out.push(0x00);
    out
}

fn nbt_str(name: &str) -> Vec<u8> {
    let mut out = (name.len() as u16).to_be_bytes().to_vec();
    out.extend_from_slice(name.as_bytes());
    out
}

fn long_tag(name: &str, value: i64) -> Vec<u8> {
    let mut out = vec![0x04];
    out.extend_from_slice(&nbt_str(name));
    out.extend_from_slice(&value.to_be_bytes());
    out
}

fn compound_tag(name: &str, entries: &[Vec<u8>]) -> Vec<u8> {
    let mut out = vec![0x0A];
    out.extend_from_slice(&nbt_str(name));
    for e in entries {
        out.extend_from_slice(e);
    }
    out.push(0x00);
    out
}

#[test]
fn scanner_streams_the_same_values_as_before() {
    let data = root(&[long_tag("InhabitedTime", 42)]);
    assert_eq!(find_inhabited_time(&data), Some(42));
    let mut cursor = std::io::Cursor::new(data);
    assert!(matches!(scan(&mut cursor), ScanResult::Found(42)));
}

#[test]
fn legacy_level_layout_is_supported() {
    let data = root(&[compound_tag("Level", &[long_tag("InhabitedTime", 7_000)])]);
    let mut cursor = std::io::Cursor::new(data);
    assert!(matches!(scan(&mut cursor), ScanResult::Found(7_000)));
}

#[test]
fn nested_decoy_is_ignored() {
    let decoy = compound_tag("structures", &[long_tag("InhabitedTime", 999_999)]);
    let data = root(&[decoy, long_tag("InhabitedTime", 0)]);
    let mut cursor = std::io::Cursor::new(data);
    assert!(matches!(scan(&mut cursor), ScanResult::Found(0)));
}

#[test]
fn missing_tag_is_absent_and_damage_is_malformed() {
    let no_tag = root(&[compound_tag("sections", &[])]);
    let mut cursor = std::io::Cursor::new(no_tag);
    assert!(matches!(scan(&mut cursor), ScanResult::Absent));

    let truncated = {
        let mut d = root(&[long_tag("InhabitedTime", 5)]);
        d.truncate(d.len() - 3);
        d
    };
    let mut cursor = std::io::Cursor::new(truncated);
    assert!(matches!(scan(&mut cursor), ScanResult::Malformed(_)));

    let not_compound = vec![0x08, 0x00, 0x01, b'x'];
    let mut cursor = std::io::Cursor::new(not_compound);
    assert!(matches!(scan(&mut cursor), ScanResult::Malformed(_)));
}
