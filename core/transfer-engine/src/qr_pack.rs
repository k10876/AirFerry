//! Pack sender QR matrices into the shared little-endian buffer layout.
//!
//! The browser WASM hot path (`SenderSessionWasm::next_qr_scratch`) and the
//! Android JNI sender (`senderNextQr`) emit the same bytes so a host can parse
//! one code or a 2×2 tile without caring which FFI produced them:
//!
//! ```text
//! [u32le count][for each matrix: u32le side + side*side bytes]
//! ```
//!
//! Each module byte is `1` = dark, `0` = light, row-major. `count` is capped
//! at [`MAX_UI_QR_COUNT`] (the on-screen 4-code layout). A zero count means
//! the session could not produce a frame this tick.

use crate::sender::SenderSession;
use crate::{Error, Result};

/// Largest QR version AirFerry will emit (ISO Version 40).
pub const MAX_QR_SIDE: usize = 177;
/// `MAX_QR_SIDE * MAX_QR_SIDE`.
pub const MAX_QR_MODULES: usize = MAX_QR_SIDE * MAX_QR_SIDE;
/// UI only offers 1 or 4 codes; 4 is the packed-buffer ceiling.
pub const MAX_UI_QR_COUNT: usize = 4;
/// Bytes that always hold `count` 4-code Version-40 matrices.
pub const QR_SCRATCH_BYTES: usize = 4 + MAX_UI_QR_COUNT * (4 + MAX_QR_MODULES);

/// Encode `count` fresh frames into `out` using the packed layout above.
///
/// `count` is clamped to `1..=MAX_UI_QR_COUNT`. Returns the number of bytes
/// written (always at least 4). The session handle is **not** thread-safe;
/// the host must serialize calls.
pub fn pack_next_qr(session: &mut SenderSession, count: u32, out: &mut [u8]) -> Result<u32> {
    let n = count.clamp(1, MAX_UI_QR_COUNT as u32) as usize;
    if out.len() < 4 {
        return Err(Error::QrBufferTooSmall {
            need: 4,
            have: out.len(),
        });
    }
    let mut pos = 4usize;
    let mut produced = 0u32;
    for _ in 0..n {
        let frame = session.next_frame()?;
        let matrix = qr_protocol::qr_render::encode(&frame.to_bytes()).map_err(Error::Protocol)?;
        let need = 4 + matrix.modules.len();
        if pos + need > out.len() {
            return Err(Error::QrBufferTooSmall {
                need: pos + need,
                have: out.len(),
            });
        }
        out[pos..pos + 4].copy_from_slice(&(matrix.size as u32).to_le_bytes());
        pos += 4;
        for (dst, &dark) in out[pos..pos + matrix.modules.len()]
            .iter_mut()
            .zip(matrix.modules.iter())
        {
            *dst = dark as u8;
        }
        pos += matrix.modules.len();
        produced += 1;
    }
    out[..4].copy_from_slice(&produced.to_le_bytes());
    Ok(pos as u32)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::descriptor::FileMeta;
    use crate::sender::{SenderConfig, SenderSession};
    use qr_protocol::SessionId;
    use raptorq_core::Config;

    fn meta_for(payload: &[u8]) -> FileMeta {
        FileMeta {
            filename: "test.bin".to_string(),
            original_size: payload.len() as u64,
            crc32: 0,
            compression: qr_protocol::compress::COMPRESSION_NONE,
            compressed_size: payload.len() as u64,
            compressed_size_known: true,
            crc32_known: false,
        }
    }

    fn session(payload: &[u8], symbol_size: u32) -> SenderSession {
        let cfg = SenderConfig {
            codec: Config::new(symbol_size).unwrap(),
            redundancy_pct: 25,
        };
        SenderSession::new(
            payload,
            SessionId::derive("test.bin", payload.len() as u64, 0, &[]),
            cfg,
            meta_for(payload),
        )
        .unwrap()
    }

    #[test]
    fn packs_one_and_four_codes() {
        let data = vec![7u8; 8_000];
        let mut s = session(&data, 512);
        let mut buf = vec![0u8; QR_SCRATCH_BYTES];
        let written = pack_next_qr(&mut s, 1, &mut buf).unwrap();
        assert!(written > 8);
        let count = u32::from_le_bytes(buf[0..4].try_into().unwrap());
        assert_eq!(count, 1);
        let side = u32::from_le_bytes(buf[4..8].try_into().unwrap());
        assert!(side >= 21 && side <= MAX_QR_SIDE as u32);
        assert_eq!(written, 4 + 4 + side * side);

        let written4 = pack_next_qr(&mut s, 4, &mut buf).unwrap();
        let count4 = u32::from_le_bytes(buf[0..4].try_into().unwrap());
        assert_eq!(count4, 4);
        assert!(written4 > written);
    }

    #[test]
    fn rejects_undersized_buffer() {
        let data = vec![1u8; 64];
        let mut s = session(&data, 512);
        let mut tiny = [0u8; 2];
        let err = pack_next_qr(&mut s, 1, &mut tiny).unwrap_err();
        match err {
            Error::QrBufferTooSmall { need: 4, have: 2 } => {}
            other => panic!("unexpected error: {other}"),
        }
    }
}
