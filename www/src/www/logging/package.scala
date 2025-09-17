package www

import fansi.Str
import www.logging.Logger.AppendableLogger
import www.logging.Logger.Stored
import www.logging.Logger.StoringLogger
import www.logging.Logger.WriterLogger

import java.io.StringWriter
import java.io.Writer

package object logging {
  type Ctx = Map[Str, Str]

  private[logging] val emptyContext: Ctx = Map.empty

  def stdout: Logger[Unit] =
    appendable(System.out).void

  def appendable[A <: Appendable](
      appendable: A,
      pattern: Pattern = Pattern.default,
      ctx: Ctx = emptyContext
  ): Logger[A] =
    new AppendableLogger(appendable, pattern, ctx)

  def writer[W <: Writer](
      writer: W = new java.io.PrintWriter(System.out),
      pattern: Pattern = Pattern.default,
      ctx: Ctx = emptyContext
  ): Logger[W] =
    new WriterLogger(new AppendableLogger(writer, pattern, ctx))

  def stringWriter(pattern: Pattern = Pattern.default, ctx: Ctx = emptyContext): Logger[StringWriter] =
    writer(new StringWriter)

  def storing(ctx: Ctx = emptyContext): Logger[Array[Stored]] =
    new StoringLogger(new Logger.Store, ctx)
}
