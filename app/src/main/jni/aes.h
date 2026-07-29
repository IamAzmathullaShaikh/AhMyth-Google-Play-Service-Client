#ifndef AES_H
#define AES_H

#include <stdint.h>
#include <stddef.h>

/* AES-128 key schedule (10 rounds) */
#define AES_KEY_SIZE      16
#define AES_BLOCK_SIZE    16
#define AES_ROUNDS        10
#define AES_EXP_KEY_SIZE  (4 * (AES_ROUNDS + 1))  /* 44 words = 176 bytes */

/**
 * Expand a 16-byte AES-128 key into the round-key schedule.
 * This is the only key-expansion entry point; decryption functions
 * consume the expanded schedule directly.
 */
void aes128_key_expand(const uint8_t key[16], uint32_t rk[44]);

/**
 * Decrypt a single 16-byte block in place using the pre-computed
 * round-key schedule.  ECB mode is simply one block; multi-block
 * callers invoke this function per block.
 */
void aes128_decrypt_block(uint8_t block[16], const uint32_t rk[44]);

/* Utility: XOR a 16-byte block with a round key (byte-addressable) */
void aes128_add_round_key(uint8_t block[16], const uint32_t rk[44], int round);

#endif /* AES_H */
