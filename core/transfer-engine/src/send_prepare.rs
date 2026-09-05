//! Native sender payload preparation (compress + CRC).
//!
//! The browser sender runs this step in JS (`compress.ts`: zstd level 1, xz
//! level 9, 70 % early-exit). Android has the native zstd/xz libraries, so the
//! JNI sender uses this module instead of shipping a second WASM compressor.
//! The on-wire algorithm tags are identical (`0` none / `1` zstd / `2` xz), so
//! any receiver decompresses the result.
//!
//! Speed is tuned for a phone Share-sheet handoff, not the Rust test-path
//! maximum (`compress.rs` `DEFAULT_LEVEL = 22`):
//! - zstd **level 1** (same as the browser worker)
//! - xz only when zstd already beat the 70 % ratio **and** the input is
//!   ≤ [`XZ_MAX_INPUT`] (xz on tens of MiB stalls the Share UI on-device)

#![cfg(not(target_arch = "wasm32"))]

use crate::{Error, Result};
use qr_protocol::compress::{
    compress, compress_with, COMPRESSION_NONE, COMPRESSION_XZ, COMPRESSION_ZSTD,
};

/// Mirror of the browser worker's zstd level.
pub const SENDER_ZSTD_LEVEL: i32 = 1;
/// Mirror of `compress.ts` `ZSTD_ALREADY_COMPRESSED_RATIO`.
pub const ZSTD_ALREADY_COMPRESSED_RATIO: f64 = 0.70;
/// Skip the slow xz pass above this input size (Android sender UX).
pub const XZ_MAX_INPUT: usize = 8 * 1024 * 1024;

/// Result of [`prepare_payload`].
#[derive(Debug, Clone)]
pub struct PreparedPayload {
    /// `COMPRESSION_*` tag written into the descriptor.
    pub algorithm: u8,
    /// CRC32 of the **original** (pre-compress) bytes.
    pub crc32: u32,
    /// Bytes that will be RaptorQ-encoded (already compressed, or raw).
    pub compressed: Vec<u8>,
}

/// Pick the smallest of raw / zstd / (optional) xz and CRC the original.
pub fn prepare_payload(raw: &[u8]) -> Result<PreparedPayload> {
    if raw.is_empty() {
        return Err(Error::NoPayload);
    }
    let crc32 = crc32fast::hash(raw);
    let zstd = compress(raw, SENDER_ZSTD_LEVEL)?;
    let zstd_ratio = zstd.len() as f64 / raw.len() as f64;

    let mut algorithm = COMPRESSION_ZSTD;
    let mut compressed = zstd;

    if zstd_ratio < ZSTD_ALREADY_COMPRESSED_RATIO && raw.len() <= XZ_MAX_INPUT {
        if let Ok(xz) = compress_with(raw, COMPRESSION_XZ) {
            if xz.len() < compressed.len() {
                algorithm = COMPRESSION_XZ;
                compressed = xz;
            }
        }
    }

    if compressed.len() >= raw.len() {
        algorithm = COMPRESSION_NONE;
        compressed = raw.to_vec();
    }

    Ok(PreparedPayload {
        algorithm,
        crc32,
        compressed,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use qr_protocol::compress::decompress_with_limit;

    #[test]
    fn empty_is_rejected() {
        assert!(matches!(prepare_payload(&[]), Err(Error::NoPayload)));
    }

    #[test]
    fn incompressible_stays_raw() {
        // SHA-256 of a counter is high-entropy; zstd level 1 cannot shrink it.
        use sha2::{Digest, Sha256};
        let mut raw = Vec::with_capacity(8192);
        for i in 0..256u32 {
            raw.extend_from_slice(&Sha256::digest(i.to_le_bytes()));
        }
        let prepared = prepare_payload(&raw).unwrap();
        assert_eq!(prepared.algorithm, COMPRESSION_NONE);
        assert_eq!(prepared.compressed, raw);
        assert_eq!(prepared.crc32, crc32fast::hash(&raw));
    }

    #[test]
    fn repetitive_text_compresses_and_roundtrips() {
        let raw = b"AirFerry share-sheet payload\n".repeat(2000);
        let prepared = prepare_payload(&raw).unwrap();
        assert_ne!(prepared.algorithm, COMPRESSION_NONE);
        assert!(prepared.compressed.len() < raw.len());
        let back = decompress_with_limit(
            &prepared.compressed,
            prepared.algorithm,
            raw.len(),
        )
        .unwrap();
        assert_eq!(back, raw);
        assert_eq!(prepared.crc32, crc32fast::hash(&raw));
    }
}
