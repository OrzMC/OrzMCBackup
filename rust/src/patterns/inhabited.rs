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
    let mut reader = NbtReader { data, pos: 0 };
    reader.read_root_inhabited().ok().flatten()
}

struct NbtReader<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> NbtReader<'a> {
    fn available(&self, n: usize) -> Result<()> {
        if self.pos + n > self.data.len() {
            return Err(anyhow!("truncated NBT"));
        }
        Ok(())
    }

    fn u8(&mut self) -> Result<u8> {
        self.available(1)?;
        let v = self.data[self.pos];
        self.pos += 1;
        Ok(v)
    }

    fn u16(&mut self) -> Result<u16> {
        self.available(2)?;
        let v = BigEndian::read_u16(&self.data[self.pos..self.pos + 2]);
        self.pos += 2;
        Ok(v)
    }

    fn i32(&mut self) -> Result<i32> {
        self.available(4)?;
        let v = BigEndian::read_i32(&self.data[self.pos..self.pos + 4]);
        self.pos += 4;
        Ok(v)
    }

    fn i64(&mut self) -> Result<i64> {
        self.available(8)?;
        let v = BigEndian::read_i64(&self.data[self.pos..self.pos + 8]);
        self.pos += 8;
        Ok(v)
    }

    fn skip(&mut self, n: usize) -> Result<()> {
        self.available(n)?;
        self.pos += n;
        Ok(())
    }

    fn read_string(&mut self) -> Result<String> {
        let len = self.u16()? as usize;
        self.available(len)?;
        let s = String::from_utf8_lossy(&self.data[self.pos..self.pos + len]).to_string();
        self.pos += len;
        Ok(s)
    }

    fn read_root_inhabited(&mut self) -> Result<Option<i64>> {
        if self.u8()? != TAG_COMPOUND {
            return Err(anyhow!("chunk payload is not a compound"));
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
                if count < 0 || count as usize > self.data.len() {
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

    fn array_len(&mut self) -> Result<usize> {
        let len = self.i32()?;
        if len < 0 || len as usize > self.data.len() {
            return Err(anyhow!("invalid array length {}", len));
        }
        Ok(len as usize)
    }
}

impl ChunkPattern for InhabitedTimePattern {
    fn matches(&self, entry: &mut McaEntry) -> Result<bool> {
        if entry.is_external()? {
            return Ok(!self.remove_unknown);
        }
        let de = entry.all_data_uncompressed()?;
        if de.is_empty() {
            return Ok(!self.remove_unknown);
        }
        if let Some(t) = find_inhabited_time(&de) {
            // Strictly greater: a chunk whose InhabitedTime equals the threshold is removed.
            // Matches Aternos/Thanos (InhabitedTimePattern.php) and OrzMCBackup semantics.
            return Ok(t > self.threshold);
        }
        Ok(!self.remove_unknown)
    }

    fn box_clone(&self) -> Box<dyn ChunkPattern> {
        Box::new(Self {
            threshold: self.threshold,
            remove_unknown: self.remove_unknown,
        })
    }
}
