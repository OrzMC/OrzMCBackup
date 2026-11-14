//! Structure-aware `InhabitedTime` scanning (WOS-3.1 / WOS-3.4).
//!
//! The tag is accepted only at the chunk root (1.18+) or at `root.Level` (<=1.17). A same-named
//! tag nested anywhere else must be ignored, otherwise a chance byte sequence could decide
//! whether a chunk is kept.

use rust_thanos::patterns::inhabited::find_inhabited_time;

fn nbt_str(name: &str) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&(name.len() as u16).to_be_bytes());
    out.extend_from_slice(name.as_bytes());
    out
}

fn long_tag(name: &str, value: i64) -> Vec<u8> {
    let mut out = vec![0x04];
    out.extend_from_slice(&nbt_str(name));
    out.extend_from_slice(&value.to_be_bytes());
    out
}

fn compound(entries: &[Vec<u8>]) -> Vec<u8> {
    let mut out = Vec::new();
    for e in entries {
        out.extend_from_slice(e);
    }
    out.push(0x00); // TAG_End
    out
}

fn root(entries: &[Vec<u8>]) -> Vec<u8> {
    let mut out = vec![0x0A, 0x00, 0x00]; // TAG_Compound + empty root name
    out.extend_from_slice(&compound(entries));
    out
}

fn compound_tag(name: &str, entries: &[Vec<u8>]) -> Vec<u8> {
    let mut out = vec![0x0A];
    out.extend_from_slice(&nbt_str(name));
    out.extend_from_slice(&compound(entries));
    out
}

#[test]
fn reads_root_level_inhabited_time() {
    assert_eq!(
        find_inhabited_time(&root(&[long_tag("InhabitedTime", 42)])),
        Some(42)
    );
}

#[test]
fn reads_legacy_level_inhabited_time() {
    let data = root(&[compound_tag("Level", &[long_tag("InhabitedTime", 7_000)])]);
    assert_eq!(find_inhabited_time(&data), Some(7_000));
}

#[test]
fn ignores_nested_same_named_tag_before_the_real_one() {
    // `structures` holds a decoy that sorts before the real root tag; a byte scan would pick it.
    let decoy = compound_tag("structures", &[long_tag("InhabitedTime", 999_999)]);
    let data = root(&[decoy, long_tag("InhabitedTime", 0)]);
    assert_eq!(
        find_inhabited_time(&data),
        Some(0),
        "only root-level / root.Level tags count"
    );
}

#[test]
fn ignores_tag_nested_two_levels_deep() {
    let deep = compound_tag(
        "a",
        &[compound_tag("b", &[long_tag("InhabitedTime", 123_456)])],
    );
    assert_eq!(find_inhabited_time(&root(&[deep])), None);
}

#[test]
fn missing_tag_returns_none() {
    assert_eq!(
        find_inhabited_time(&root(&[compound_tag("sections", &[])])),
        None
    );
}

#[test]
fn malformed_nbt_returns_none() {
    // Truncated root compound: callers must fall back to the safe-keep rule (WOS-3.3).
    let mut data = root(&[long_tag("InhabitedTime", 5)]);
    data.truncate(data.len() - 3);
    assert_eq!(find_inhabited_time(&data), None);
    assert_eq!(find_inhabited_time(b"not-nbt"), None);
}

#[test]
fn payload_that_is_not_a_compound_returns_none() {
    // Old minimal payloads (a bare tag without the root compound header) are not valid chunk NBT.
    let mut data = vec![0x04];
    data.extend_from_slice(&nbt_str("InhabitedTime"));
    data.extend_from_slice(&42i64.to_be_bytes());
    assert_eq!(find_inhabited_time(&data), None);
}
