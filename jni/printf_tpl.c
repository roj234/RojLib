
#include <stdint.h>
#include <stddef.h>

// C语言真的该早点死了, 还有这死妈的null terminate string
static const char CONCAT(PREFIX, digitsx)[16] = {
    '0', '1', '2', '3', '4', '5', '6', '7', 
    '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
};
static const char CONCAT(PREFIX, digitsX)[16] = {
    '0', '1', '2', '3', '4', '5', '6', '7', 
    '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
};

char* CONCAT(PREFIX, itoa)(
	char* str,
	int8_t digits,
	uint32_t val
) {
    do {
        uint32_t tmp = val / 10;
        *--str = CONCAT(PREFIX, digitsx)[val - tmp * 10];
        val = tmp;

        if (--digits == 0) *--str = '.';
    } while (val != 0);

    while (digits >= 0) {
        *(--str) = '0';
        if (--digits == 0) *(--str) = '.';
    }

    return str;
}

char* CONCAT(PREFIX, decimalToString)(
	char* str,
	int8_t digits,
	int32_t value
) {
    bool isNegative = value < 0;
    if (isNegative) value = -value;

    *(--str) = '\0';
    str = CONCAT(PREFIX, itoa)(str, digits, value);
    if (isNegative) *(--str) = '-';

    return str;
}

static inline void CONCAT(PREFIX, putc)(char *buf, size_t size, size_t *pos, char c) {
    if (*pos < size - 1) buf[*pos] = c;
    (*pos)++;
}

int_fast16_t CONCAT(PREFIX, snprintf)(char* buf, size_t size, const char* fmt, ...) {
    char sb[12]; // 214748364.8
    sb[11] = 0;

    va_list args;
    va_start(args, fmt);
    size_t pos = 0;

    char c;
    while ((c = *fmt++) != '\0') {
        if (c != '%') { CONCAT(PREFIX, putc)(buf, size, &pos, c); continue; }

        int width = 0;
        char pad = ' ';
        if (*fmt == '0') {
            pad = '0';
            fmt++;
        }
        while (*fmt >= '0' && *fmt <= '9') {
            width = width * 10 + (*fmt++ - '0');
        }

        int dot = -1;
        char* str;
        int len;

        switch (*fmt++) {
            case 'p': {
                dot = *fmt - '0';
                fmt += 2; // 忽略了错误处理
            }
            case 'd': {
                uint32_t val = va_arg(args, uint32_t);

                if ((int32_t)val < 0) {
                    CONCAT(PREFIX, putc)(buf, size, &pos, '-');
                    val = -val;
                }

                str = CONCAT(PREFIX, decimalToString)(&sb[(sizeof(sb)/sizeof(sb[0])) - 1], dot, val);

                len = sb + (sizeof(sb)/sizeof(sb[0])) - 1 - str;
                goto flush_string;
            }
            case 'x':
            case 'X': {
                uint32_t val = va_arg(args, uint32_t);
                str = &sb[(sizeof(sb)/sizeof(sb[0])) - 1];

                const char* digits = fmt[-1] == 'X' ? CONCAT(PREFIX, digitsX) : CONCAT(PREFIX, digitsx);
                do {
                    *--str = digits[val & 0xF];
                    val >>= 4;
                } while (val);

                len = sb + (sizeof(sb)/sizeof(sb[0])) - 1 - str;
                goto flush_string;
            }
            case 's': {
                str = va_arg(args, char *);
#ifdef char
                if (!str) str = L"NULL";

                len = wcslen(str);
#else
                if (!str) str = "NULL";

                len = strlen(str);
#endif

                flush_string:
                while (len < width) {
                    CONCAT(PREFIX, putc)(buf, size, &pos, pad);
                    len++;
                }

                while (*str) CONCAT(PREFIX, putc)(buf, size, &pos, *str++);
                break;
            }
            case 'c': {
                char c1 = (char)va_arg(args, int);
                CONCAT(PREFIX, putc)(buf, size, &pos, c1);
                break;
            }
            default:
                CONCAT(PREFIX, putc)(buf, size, &pos, fmt[-1]);
            break;
        }
    }

    if (size > 0) {
        buf[pos < size ? pos : size - 1] = '\0';
    }

    va_end(args);
    return (int_fast16_t)pos;
}