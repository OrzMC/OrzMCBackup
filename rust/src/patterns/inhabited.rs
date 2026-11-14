use crate::mca::entry::McaEntry;
use crate::patterns::ChunkPattern;
use anyhow::{anyhow, Result};
use byteorder::{BigEndian, ByteOrder};

pub struct InhabitedTimePattern {
    threshold: i64,
    remove_unknown: bool,
}

impl InhabitedTimePattern {
    pub fn new(threshold: i64, remove_unknown: bool) -> Self {
        Self {
            threshold,
            remove_unknown,
        }
    }
}

const LONG_TAG: u8 = 4;
const TAG_END: u8 = 0;
const TAG_COMPOUND: u8 = 10;
const INHABITED_TIME: &str = "InhabitedTime";

/// Structure-aware scan for `InhabitedTime` (WOS-3.1/WOS-3.4).
///
/// Only the chunk root compound is walked (payloads are skipped by length, never materialised).
/// The tag is accepted at the root (1.18+) or at `root.Level` (<=1.17) and **ignored** anywhere
/// else - a same-named tag nested in `structures`, a block entity or a mod payload must not
/// decide whether a chunk is kept.
///
/// Returns `None` when the tag is absent *or* the NBT cannot be parsed; callers then apply the
/// safe-keep rule (WOS-3.3).
pub fn find_inhabited_time(data: &[u8]) -> Option<i64> {
    match scan(&mut std::io::Cursor::new(data)) {
        ScanResult::Found(v) => Some(v),
        _ => None,
    }
}

/// Result of a structural scan (mirrors the Kotlin `ChunkNbtScanner.ScanResult`).
pub enum ScanResult {
    /// No `InhabitedTime` at the accepted positions (WOS-3.3 applies).
    Absent,
    Found(i64),
    /// The payload is not valid chunk NBT; callers keep it and report an error (WOS-6.6).
    Malformed(String),
}

/// Streaming scan (WOS-10.2): reads only as far as the tag, so memory stays bounded.
pub fn scan(input: &mut dyn std::io::Read) -> ScanResult {
    let mut reader = NbtReader { input, pos: 0 };
    match reader.read_root_inhabited() {
        Ok(Some(v)) => ScanResult::Found(v),
        Ok(None) => ScanResult::Absent,
        Err(e) => ScanResult::Malformed(e.to_string()),
    }
}

struct NbtReader<'a> {
    input: &'a mut dyn std::io::Read,
    pos: u64,
}

impl<'a> NbtReader<'a> {
    fn bytes(&mut self, n: usize) -> Result<Vec<u8>> {
        let mut buf = vec![0u8; n];
        self.input.read_exact(&mut buf)?;
        self.pos += n as u64;
        Ok(buf)
    }

    fn u8(&mut self) -> Result<u8> {
        let mut b = [0u8; 1];
        self.input.read_exact(&mut b)?;
        self.pos += 1;
        Ok(b[0])
    }

    fn u16(&mut self) -> Result<u16> {
        let b = self.bytes(2)?;
        Ok(BigEndian::read_u16(&b))
    }

    fn i32(&mut self) -> Result<i32> {
        let b = self.bytes(4)?;
        Ok(BigEndian::read_i32(&b))
    }

    fn i64(&mut self) -> Result<i64> {
        let b = self.bytes(8)?;
        Ok(BigEndian::read_i64(&b))
    }

    fn skip(&mut self, n: u64) -> Result<()> {
        let mut remaining = n;
        let mut scratch = [0u8; 8192];
        while remaining > 0 {
            let want = remaining.min(scratch.len() as u64) as usize;
            self.input.read_exact(&mut scratch[..want])?;
            remaining -= want as u64;
            self.pos += want as u64;
        }
        Ok(())
    }

    fn read_string(&mut self) -> Result<String> {
        let len = self.u16()? as usize;
        let b = self.bytes(len)?;
        Ok(String::from_utf8_lossy(&b).to_string())
    }

    fn read_root_inhabited(&mut self) -> Result<Option<i64>> {
        if self.u8()? != TAG_COMPOUND {
            return Err(anyhow!("chunk payload is not an NBT compound"));
        }
        let _root_name = self.read_string()?;
        self.read_compound_body(0, None)
    }

    fn read_compound_body(&mut self, depth: usize, parent: Option<&str>) -> Result<Option<i64>> {
        loop {
            let tag = self.u8()?;
            if tag == TAG_END {
                return Ok(None);
            }
            let name = self.read_string()?;
            let accepted = tag == LONG_TAG
                && name == INHABITED_TIME
                && (depth == 0 || (depth == 1 && parent == Some("Level")));
            if accepted {
                let value = self.i64()?;
                self.skip_remaining_entries()?;
                return Ok(Some(value));
            }
            if tag == TAG_COMPOUND && depth == 0 && name == "Level" {
                if let Some(value) = self.read_compound_body(1, Some("Level"))? {
                    self.skip_remaining_entries()?;
                    return Ok(Some(value));
                }
                continue;
            }
            self.skip_payload(tag)?;
        }
    }

    /// Validates the remainder of the compound so truncation is still detected.
    fn skip_remaining_entries(&mut self) -> Result<()> {
        loop {
            let tag = self.u8()?;
            if tag == TAG_END {
                return Ok(());
            }
            let _name = self.read_string()?;
            self.skip_payload(tag)?;
        }
    }

    fn skip_payload(&mut self, tag: u8) -> Result<()> {
        match tag {
            1 => self.skip(1),
            2 => self.skip(2),
            3 | 5 => self.skip(4),
            4 | 6 => self.skip(8),
            7 => {
                let len = self.array_len()?;
                self.skip(len)
            }
            8 => {
                let _ = self.read_string()?;
                Ok(())
            }
            9 => {
                let elem = self.u8()?;
                let count = self.i32()?;
                if !(0..=10_000_000).contains(&count) {
                    return Err(anyhow!("invalid list length {}", count));
                }
                for _ in 0..count {
                    self.skip_payload(elem)?;
                }
                Ok(())
            }
            10 => loop {
                let t = self.u8()?;
                if t == TAG_END {
                    return Ok(());
                }
                let _name = self.read_string()?;
                self.skip_payload(t)?;
            },
            11 => {
                let len = self.array_len()?;
                self.skip(len * 4)
            }
            12 => {
                let len = self.array_len()?;
                self.skip(len * 8)
            }
            other => Err(anyhow!("unsupported NBT tag id {}", other)),
        }
    }

    fn array_len(&mut self) -> Result<u64> {
        let len = self.i32()?;
        if len < 0 {
            return Err(anyhow!("invalid array length {}", len));
        }
        Ok(len as u64)
    }
}

impl ChunkPattern for InhabitedTimePattern {
    fn matches(&self, entry: &mut McaEntry) -> Result<bool> {
        if entry.is_external()? {
            return Ok(!self.remove_unknown);
        }
        // Streams the decompressed payload (WOS-10.2): memory stays bounded by one compressed
        // block instead of the whole decompressed chunk, and we stop at the root tag.
        let mut stream = entry.open_data_stream()?;
        match scan(&mut *stream) {
            // Strictly greater: a chunk whose InhabitedTime equals the threshold is removed.
            // Matches Aternos/Thanos (InhabitedTimePattern.php) and OrzMCBackup semantics.
            ScanResult::Found(t) => Ok(t > self.threshold),
            ScanResult::Absent => Ok(!self.remove_unknown),
            // Unparseable chunk: keep it (WOS-6.6); the error makes the engine record a Pattern error.
            ScanResult::Malformed(reason) => Err(anyhow!("malformed chunk NBT: {}", reason)),
        }
    }

    fn box_clone(&self) -> Box<dyn ChunkPattern> {
        Box::new(Self {
            threshold: self.threshold,
            remove_unknown: self.remove_unknown,
        })
    }
}
