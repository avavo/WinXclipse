#pragma once
// ─────────────────────────────────────────────────────────────────────────────
//  xxhash.h  –  minimal XXH64 implementation, self-contained, MIT licence.
//
//  Only the 64-bit streaming-less hash is provided; that is all we need for
//  hashing texture upload regions.
//
//  Based on xxHash r2.0.2 specification by Yann Collet.
//  https://github.com/Cyan4973/xxHash
// ─────────────────────────────────────────────────────────────────────────────

#include <cstdint>
#include <cstddef>
#include <cstring>

namespace xxhash {

static constexpr uint64_t PRIME1 = 0x9E3779B185EBCA87ULL;
static constexpr uint64_t PRIME2 = 0xC2B2AE3D27D4EB4FULL;
static constexpr uint64_t PRIME3 = 0x165667B19E3779F9ULL;
static constexpr uint64_t PRIME4 = 0x85EBCA77C2B2AE63ULL;
static constexpr uint64_t PRIME5 = 0x27D4EB2F165667C5ULL;

static inline uint64_t rotl64(uint64_t x, int r) {
    return (x << r) | (x >> (64 - r));
}

static inline uint64_t read64(const void* p) {
    uint64_t v;
    __builtin_memcpy(&v, p, 8);
    return v;
}

static inline uint32_t read32(const void* p) {
    uint32_t v;
    __builtin_memcpy(&v, p, 4);
    return v;
}

static inline uint64_t round64(uint64_t acc, uint64_t input) {
    acc += input * PRIME2;
    acc  = rotl64(acc, 31);
    acc *= PRIME1;
    return acc;
}

static inline uint64_t merge_round(uint64_t acc, uint64_t val) {
    val  = round64(0, val);
    acc ^= val;
    acc  = acc * PRIME1 + PRIME4;
    return acc;
}

// One-shot hash.  seed = 0 is the default used everywhere in this layer.
static inline uint64_t xxh64(const void* data, size_t len, uint64_t seed = 0) {
    const uint8_t* p   = static_cast<const uint8_t*>(data);
    const uint8_t* end = p + len;
    uint64_t h64;

    if (len >= 32) {
        uint64_t v1 = seed + PRIME1 + PRIME2;
        uint64_t v2 = seed + PRIME2;
        uint64_t v3 = seed;
        uint64_t v4 = seed - PRIME1;

        do {
            v1 = round64(v1, read64(p));  p += 8;
            v2 = round64(v2, read64(p));  p += 8;
            v3 = round64(v3, read64(p));  p += 8;
            v4 = round64(v4, read64(p));  p += 8;
        } while (p <= end - 32);

        h64 = rotl64(v1, 1)  + rotl64(v2, 7) +
              rotl64(v3, 12) + rotl64(v4, 18);
        h64 = merge_round(h64, v1);
        h64 = merge_round(h64, v2);
        h64 = merge_round(h64, v3);
        h64 = merge_round(h64, v4);
    } else {
        h64 = seed + PRIME5;
    }

    h64 += static_cast<uint64_t>(len);

    while (p + 8 <= end) {
        uint64_t k1 = round64(0, read64(p));
        h64 ^= k1;
        h64  = rotl64(h64, 27) * PRIME1 + PRIME4;
        p   += 8;
    }
    if (p + 4 <= end) {
        h64 ^= static_cast<uint64_t>(read32(p)) * PRIME1;
        h64  = rotl64(h64, 23) * PRIME2 + PRIME3;
        p   += 4;
    }
    while (p < end) {
        h64 ^= static_cast<uint64_t>(*p) * PRIME5;
        h64  = rotl64(h64, 11) * PRIME1;
        ++p;
    }

    // avalanche
    h64 ^= h64 >> 33;
    h64 *= PRIME2;
    h64 ^= h64 >> 29;
    h64 *= PRIME3;
    h64 ^= h64 >> 32;
    return h64;
}

} // namespace xxhash
