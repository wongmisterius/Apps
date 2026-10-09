// SPDX-License-Identifier: GPL-3.0-or-later
// eden-ps4: 32-bit wide-character memory functions.
//
// This toolchain compiles with a 4-byte wchar_t, and libc++ turns std::find / std::ranges::find
// over any 4-byte trivially comparable type (u32, SlotId, ...) into __builtin_wmemchr, i.e. a call
// to the C library's wmemchr. The console's libc evidently does not search 4-byte units: in test 19
// an id pushed into a page-table vector was not found right after unless it was the vector's first
// element, which corrupted the texture cache (the race crashes) and can just as well corrupt anything
// else that searches 32-bit arrays (SPIR-V words in the shader recompiler, for one). Defining them
// here, in the executable, makes every caller use these instead.
//
// Built with -fno-builtin so the loops below are not turned back into calls to themselves.

#include <cstddef>
#include <cstring>
#include <cwchar>

extern "C" {

wchar_t* wmemchr(const wchar_t* s, wchar_t c, std::size_t n) {
    for (; n != 0; --n, ++s) {
        if (*s == c) {
            return const_cast<wchar_t*>(s);
        }
    }
    return nullptr;
}

int wmemcmp(const wchar_t* a, const wchar_t* b, std::size_t n) {
    for (; n != 0; --n, ++a, ++b) {
        if (*a != *b) {
            return *a < *b ? -1 : 1;
        }
    }
    return 0;
}

wchar_t* wmemcpy(wchar_t* dst, const wchar_t* src, std::size_t n) {
    std::memcpy(dst, src, n * sizeof(wchar_t));
    return dst;
}

wchar_t* wmemmove(wchar_t* dst, const wchar_t* src, std::size_t n) {
    std::memmove(dst, src, n * sizeof(wchar_t));
    return dst;
}

wchar_t* wmemset(wchar_t* dst, wchar_t c, std::size_t n) {
    for (std::size_t i = 0; i < n; ++i) {
        dst[i] = c;
    }
    return dst;
}

std::size_t wcslen(const wchar_t* s) {
    const wchar_t* p = s;
    while (*p != 0) {
        ++p;
    }
    return static_cast<std::size_t>(p - s);
}

int wcscmp(const wchar_t* a, const wchar_t* b) {
    for (;; ++a, ++b) {
        if (*a != *b) {
            return *a < *b ? -1 : 1;
        }
        if (*a == 0) {
            return 0;
        }
    }
}

int wcsncmp(const wchar_t* a, const wchar_t* b, std::size_t n) {
    for (; n != 0; --n, ++a, ++b) {
        if (*a != *b) {
            return *a < *b ? -1 : 1;
        }
        if (*a == 0) {
            return 0;
        }
    }
    return 0;
}

wchar_t* wcschr(const wchar_t* s, wchar_t c) {
    for (;; ++s) {
        if (*s == c) {
            return const_cast<wchar_t*>(s);
        }
        if (*s == 0) {
            return nullptr;
        }
    }
}

} // extern "C"
