package dbis.stark.spatial

import scala.reflect.ClassTag
import scala.collection.mutable.Map

import scala.collection.JavaConverters._

import org.apache.spark.rdd.RDD

import dbis.stark.spatial.partitioner.BSP
import dbis.stark.STObject


object BSPartitioner {
  /**
   * Compute the bounds of a cell with the given ID
   * @param id The ID of the cell to compute the bounds for
   */
  protected[spatial] def getCellBounds(id: Int, xCells: Int, yCells: Int, _sideLength: Double, minX: Double, minY: Double): NRectRange = {
      
    val dy = id / xCells
    val dx = id % yCells
    
    val llx = dx * _sideLength + minX
    val lly = dy * _sideLength + minY
    
    val urx = llx + _sideLength
    val ury = lly + _sideLength
      
    NRectRange(id, NPoint(llx, lly), NPoint(urx, ury))
  }
}

/**
 * A cost based binary space partitioner based on the paper
 * MR-DBSCAN: A scalable MapReduce-based DBSCAN algorithm for heavily skewed data
 * by He, Tan, Luo, Feng, Fan 
 * 
 * @param rdd The RDD to partition
 * @param sideLength side length of a quadratic cell - defines granularity
 * @param maxCostPerPartition Maximum cost a partition should have - here: number of elements  
 */
class BSPartitioner[G <: STObject : ClassTag, V: ClassTag](
    rdd: RDD[(G,V)],
    _sideLength: Double,
    _maxCostPerPartition: Double,
    withExtent: Boolean,
    _minX: Double,
    _maxX: Double,
    _minY: Double,
    _maxY: Double) extends SpatialPartitioner(rdd, _minX, _maxX, _minY, _maxY) {

  def this(rdd: RDD[(G,V)],
      _sideLength: Double,
      _maxCostPerPartition: Double,
      withExtent: Boolean, 
      minMax: (Double, Double, Double, Double)) = 
    this(rdd, _sideLength, _maxCostPerPartition, withExtent, minMax._1, minMax._2, minMax._3, minMax._4)  
  
  def this(rdd: RDD[(G,V)],
      _sideLength: Double,
      _maxCostPerPartition: Double,
      withExtent: Boolean = false) = 
    this(rdd, _sideLength, _maxCostPerPartition, withExtent, SpatialPartitioner.getMinMax(rdd))

  
  lazy val maxCostPerPartition = _maxCostPerPartition
  lazy val sideLength = _sideLength
  
  protected[spatial] var numXCells = Math.ceil(math.abs(maxX - minX) / _sideLength).toInt
  protected[spatial] var numYCells = Math.ceil(math.abs(maxY - minY) / _sideLength).toInt
  
  
  
  /**
   * The cells which contain elements and the number of elements
   * 
   * We iterate over all elements in the RDD, determine to which
   * cell it belongs and then simply aggregate by cell
   */
  protected[spatial] var cells: Array[(Cell, Int)] = {
    
    val themap = Map.empty[Cell, Int]
    
    (0 until numXCells * numYCells).foreach { i => 
      val cellBounds = BSPartitioner.getCellBounds(i, numXCells, numYCells, _sideLength, minX, minY)
      
      val cell = Cell(cellBounds)
      
      themap += (cell -> 0)
    }
    
    rdd.map { case (g,v) =>
      val p = g.getCentroid
      
      val env = g.getEnvelopeInternal
      val extent = NRectRange(NPoint(env.getMinX, env.getMinY), NPoint(env.getMaxX, env.getMaxY))
      
      val x = (math.abs(p.getX - minX).toInt / _sideLength).toInt
      val y = (math.abs(p.getY - minY).toInt / _sideLength).toInt
      
      val cellId = y * numXCells + x
      
      (cellId,(1, extent))
    }
    .reduceByKey{ case ((lCnt, lExtent), (rCnt, rExtent)) => 
      val cnt = lCnt + rCnt
      
      val extent = lExtent.extend(rExtent)
      
      (cnt, extent)
      
    }
    .map { case (id, cntAndExtent) => (BSPartitioner.getCellBounds(id, numXCells, numYCells, _sideLength, minX, minY), cntAndExtent) }
    .collect
    .foreach { case (cellBounds, (cnt,extent)) =>
      val cell = Cell(cellBounds, extent)
      themap(cell) += cnt 
    }
    
    themap.toArray.sortBy(_._1.range.id)
  }

  
  protected[spatial] var bsp = new BSP(
      Array(minX, minY), 
      Array(maxX, maxY), 
      cells, // for BSP we only need calculated cell sizes and their respective counts 
      _sideLength, 
      _maxCostPerPartition,
      withExtent)  
    
  override def partitionBounds(idx: Int) = bsp.partitions(idx)
  override def partitionExtent(idx: Int) = bsp.partitions(idx).extent
  
  def printPartitions(fName: java.nio.file.Path) {
    val list = bsp.partitions.map(_.range).map { p => s"${p.ll(0)},${p.ll(1)},${p.ur(0)},${p.ur(1)}" }.asJava    
    java.nio.file.Files.write(fName, list, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE) 
      
  } 
    
  def printHistogram(fName: java.nio.file.Path) {
    
    println(s"num in hist: ${cells.map(_._2).sum}")
    
    
    val list = cells.map(_._1.range).map { case c => s"${c.ll(0)},${c.ll(1)},${c.ur(0)},${c.ur(1)}" }.toList.asJava    
    java.nio.file.Files.write(fName, list, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE) 
      
  } 
  
  override def numPartitions: Int = bsp.partitions.size
  
  override def getPartition(key: Any): Int = {
    val g = key.asInstanceOf[G]
//    println(s"get partition for key $g")

    /* XXX: This will throw an error if the geometry is outside of our partitions!
     * However, this should not happen, because the partitioner is specially for a given RDD
     * which by definition is immutable and the partitions should cover the complete data space 
     * of the RDD's content
     */
//    bsp.partitions.find { x => ??? }
    
    val part = bsp.partitions.find{ p =>
      val c = g.getCentroid
      p.range.contains(NPoint(c.getX, c.getY)) 
    }
    
    
    if(part.isEmpty) {
    
      println(bsp.partitions.mkString("\n"))
      println(bsp.partitionStats)
      val histoFile = java.nio.file.Files.createTempFile(new java.io.File(System.getProperty("user.home")).toPath(), "stark_histogram", null)
      val partitionFile = java.nio.file.Files.createTempFile(new java.io.File(System.getProperty("user.home")).toPath(), "stark_partitions", null)
      
      println(s"saving historgram to $histoFile")
      printHistogram(histoFile)
      
      println(s"saving partitions to $partitionFile")
      printPartitions(partitionFile)
      
      
      throw new IllegalStateException(s"$g is not in any partition!")
    } else {
        part.get.range.id
    }
    
    
  }
}
