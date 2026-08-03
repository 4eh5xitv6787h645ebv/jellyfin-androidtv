package org.jellyfin.androidtv.integration.canopy

/**
 * Rejects ambiguous JSON objects before kotlinx.serialization applies its last-value-wins policy.
 *
 * Canopy responses are already byte and depth bounded by [CanopyClient]. This parser keeps only one
 * bounded key set per live object and never materializes values. Rejecting duplicate additive keys as
 * well as duplicate v1 keys is deliberately fail-closed: a future producer can always emit one
 * unambiguous value without requiring a client-side schema registry.
 */
internal object CanopyDuplicateKeyGuard {
	fun accepts(input: String): Boolean = Parser(input).accepts()

	private class Parser(private val input: String) {
		private var index = 0

		fun accepts(): Boolean = try {
			skipWhitespace()
			readValue(depth = 0)
			skipWhitespace()
			index == input.length
		} catch (_: InvalidJson) {
			false
		}

		private fun readValue(depth: Int) {
			if (depth > MAX_DEPTH || index >= input.length) invalid()
			when (input[index]) {
				'{' -> readObject(depth + 1)
				'[' -> readArray(depth + 1)
				'"' -> readString(decode = false)
				't' -> readLiteral("true")
				'f' -> readLiteral("false")
				'n' -> readLiteral("null")
				'-' -> readNumber()
				in '0'..'9' -> readNumber()
				else -> invalid()
			}
		}

		private fun readObject(depth: Int) {
			expect('{')
			skipWhitespace()
			if (consume('}')) return
			val keys = mutableSetOf<String>()
			while (true) {
				if (index >= input.length || input[index] != '"') invalid()
				val key = checkNotNull(readString(decode = true))
				if (!keys.add(key)) invalid()
				skipWhitespace()
				expect(':')
				skipWhitespace()
				readValue(depth)
				skipWhitespace()
				if (consume('}')) return
				expect(',')
				skipWhitespace()
			}
		}

		private fun readArray(depth: Int) {
			expect('[')
			skipWhitespace()
			if (consume(']')) return
			while (true) {
				readValue(depth)
				skipWhitespace()
				if (consume(']')) return
				expect(',')
				skipWhitespace()
			}
		}

		private fun readString(decode: Boolean): String? {
			expect('"')
			val decoded = if (decode) StringBuilder() else null
			while (index < input.length) {
				val character = input[index++]
				when {
					character == '"' -> return decoded?.toString()
					character == '\\' -> readEscape(decoded)
					character.code < CONTROL_CHARACTER_LIMIT -> invalid()
					else -> decoded?.append(character)
				}
			}
			invalid()
		}

		private fun readEscape(decoded: StringBuilder?) {
			if (index >= input.length) invalid()
			when (val escape = input[index++]) {
				'"', '\\', '/' -> decoded?.append(escape)
				'b' -> decoded?.append('\b')
				'f' -> decoded?.append('\u000c')
				'n' -> decoded?.append('\n')
				'r' -> decoded?.append('\r')
				't' -> decoded?.append('\t')
				'u' -> decoded?.append(readUnicodeEscape())
				else -> invalid()
			}
		}

		private fun readUnicodeEscape(): Char {
			if (index + UNICODE_ESCAPE_DIGITS > input.length) invalid()
			var value = 0
			repeat(UNICODE_ESCAPE_DIGITS) {
				value = (value shl 4) or input[index++].hexValue()
			}
			return value.toChar()
		}

		private fun readNumber() {
			consume('-')
			if (index >= input.length) invalid()
			if (consume('0')) {
				if (index < input.length && input[index] in '0'..'9') invalid()
			} else {
				if (input[index] !in '1'..'9') invalid()
				readDigits()
			}
			if (consume('.')) {
				if (index >= input.length || input[index] !in '0'..'9') invalid()
				readDigits()
			}
			if (index < input.length && (input[index] == 'e' || input[index] == 'E')) {
				index++
				if (index < input.length && (input[index] == '+' || input[index] == '-')) index++
				if (index >= input.length || input[index] !in '0'..'9') invalid()
				readDigits()
			}
		}

		private fun readDigits() {
			while (index < input.length && input[index] in '0'..'9') index++
		}

		private fun readLiteral(literal: String) {
			if (!input.regionMatches(index, literal, 0, literal.length)) invalid()
			index += literal.length
		}

		private fun skipWhitespace() {
			while (index < input.length && input[index] in JSON_WHITESPACE) index++
		}

		private fun expect(expected: Char) {
			if (!consume(expected)) invalid()
		}

		private fun consume(expected: Char): Boolean {
			if (index >= input.length || input[index] != expected) return false
			index++
			return true
		}

		private fun Char.hexValue(): Int = when (this) {
			in '0'..'9' -> code - '0'.code
			in 'a'..'f' -> code - 'a'.code + 10
			in 'A'..'F' -> code - 'A'.code + 10
			else -> invalid()
		}

		private fun invalid(): Nothing = throw InvalidJson
	}

	private object InvalidJson : RuntimeException()

	private const val MAX_DEPTH = 8
	private const val CONTROL_CHARACTER_LIMIT = 0x20
	private const val UNICODE_ESCAPE_DIGITS = 4
	private val JSON_WHITESPACE = charArrayOf(' ', '\t', '\n', '\r')
}
