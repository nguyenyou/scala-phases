package www.logging

import sourcecode.Enclosing
import sourcecode.File
import sourcecode.Line

import java.time.Instant

final class Metadata(
    val instant: Instant,
    val logLevel: LogLevel,
    val line: Line,
    val file: File,
    val enclosing: Enclosing
)
