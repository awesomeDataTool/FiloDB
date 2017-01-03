package filodb.core.query

import com.googlecode.javaewah.EWAHCompressedBitmap
import java.nio.ByteBuffer
import org.velvia.filo._
import scala.collection.mutable.BitSet

import filodb.core.binaryrecord.{BinaryRecord, RecordSchema}
import filodb.core.metadata.Column
import filodb.core.store.{ChunkSet, ChunkSetInfo}
import filodb.core.Types.ColumnId

/**
 * ChunkSetReader aggregates incoming chunks during query/read time and provides an Iterator[RowReader]
 * for iterating through the chunkset row-wise, or individual chunks can also be accessed.
 *
 * TODO: create readers for the chunks as they come in, instead of when rowIterator is called
 */
class ChunkSetReader(val info: ChunkSetInfo, skips: EWAHCompressedBitmap, classes: Array[Class[_]]) {
  import ChunkSetReader._

  private final val bufs = new Array[ByteBuffer](classes.size)
  private final val len = info.numRows
  private final val bitset = new BitSet

  skips.setSizeInBits(len, false)

  def addChunk(colNo: Int, bytes: ByteBuffer): Unit = {
    bufs(colNo) = bytes
    bitset += colNo
  }

  def chunks: Array[ByteBuffer] = bufs

  def isFull: Boolean = bitset.size >= bufs.size

  /**
   * Iterates over rows in this chunkset, skipping over any rows defined in skiplist.
   * Creates new RowReaders every time, so that multiple calls could be made.
   */
  def rowIterator(readerFactory: RowReaderFactory = DefaultReaderFactory): Iterator[RowReader] = {
    val reader = readerFactory(bufs, classes, len)
    if (skips.isEmpty) {
      // This simplified iterator is MUCH faster than the skip-checking one
      new Iterator[RowReader] {
        private var i = 0
        final def hasNext: Boolean = i < len
        final def next: RowReader = {
          reader.setRowNo(i)
          i += 1
          reader
        }
      }
    } else {
      new Iterator[RowReader] {
        private final val rowNumIterator = skips.clearIntIterator()
        final def hasNext: Boolean = rowNumIterator.hasNext
        final def next: RowReader = {
          reader.setRowNo(rowNumIterator.next)
          reader
        }
      }
    }
  }
}

object ChunkSetReader {
  import PartitionChunkIndex.emptySkips

  type RowReaderFactory = (Array[ByteBuffer], Array[Class[_]], Int) => FiloRowReader

  val DefaultReaderFactory: RowReaderFactory =
    (bytes, clazzes, len) => new FastFiloRowReader(bytes, clazzes, len)

  def apply(chunkSet: ChunkSet,
            schema: Seq[Column],
            skips: EWAHCompressedBitmap = emptySkips): ChunkSetReader = {
    val clazzes = schema.map(_.columnType.clazz).toArray
    val nameToPos = schema.zipWithIndex.map { case (c, i) => (c.name -> i) }.toMap
    val reader = new ChunkSetReader(chunkSet.info, skips, clazzes)
    chunkSet.chunks.foreach { case (colName, bytes) => reader.addChunk(nameToPos(colName), bytes) }
    reader
  }
}