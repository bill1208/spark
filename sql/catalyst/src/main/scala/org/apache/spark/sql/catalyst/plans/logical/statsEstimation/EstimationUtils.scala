/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.plans.logical.statsEstimation

import scala.collection.mutable.ArrayBuffer
import scala.math.BigDecimal.RoundingMode

import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeMap}
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.types.{DecimalType, _}


object EstimationUtils {

  /** Check if each plan has rowCount in its statistics. */
  def rowCountsExist(plans: LogicalPlan*): Boolean =
    plans.forall(_.stats.rowCount.isDefined)

  /** Check if each attribute has column stat in the corresponding statistics. */
  def columnStatsExist(statsAndAttr: (Statistics, Attribute)*): Boolean = {
    statsAndAttr.forall { case (stats, attr) =>
      stats.attributeStats.contains(attr)
    }
  }

  def nullColumnStat(dataType: DataType, rowCount: BigInt): ColumnStat = {
    ColumnStat(distinctCount = 0, min = None, max = None, nullCount = rowCount,
      avgLen = dataType.defaultSize, maxLen = dataType.defaultSize)
  }

  /**
   * Updates (scales down) the number of distinct values if the number of rows decreases after
   * some operation (such as filter, join). Otherwise keep it unchanged.
   */
  def updateNdv(oldNumRows: BigInt, newNumRows: BigInt, oldNdv: BigInt): BigInt = {
    if (newNumRows < oldNumRows) {
      ceil(BigDecimal(oldNdv) * BigDecimal(newNumRows) / BigDecimal(oldNumRows))
    } else {
      oldNdv
    }
  }

  def ceil(bigDecimal: BigDecimal): BigInt = bigDecimal.setScale(0, RoundingMode.CEILING).toBigInt()

  /** Get column stats for output attributes. */
  def getOutputMap(inputMap: AttributeMap[ColumnStat], output: Seq[Attribute])
    : AttributeMap[ColumnStat] = {
    AttributeMap(output.flatMap(a => inputMap.get(a).map(a -> _)))
  }

  def getOutputSize(
      attributes: Seq[Attribute],
      outputRowCount: BigInt,
      attrStats: AttributeMap[ColumnStat] = AttributeMap(Nil)): BigInt = {
    // We assign a generic overhead for a Row object, the actual overhead is different for different
    // Row format.
    val sizePerRow = 8 + attributes.map { attr =>
      if (attrStats.contains(attr)) {
        attr.dataType match {
          case StringType =>
            // UTF8String: base + offset + numBytes
            attrStats(attr).avgLen + 8 + 4
          case _ =>
            attrStats(attr).avgLen
        }
      } else {
        attr.dataType.defaultSize
      }
    }.sum

    // Output size can't be zero, or sizeInBytes of BinaryNode will also be zero
    // (simple computation of statistics returns product of children).
    if (outputRowCount > 0) outputRowCount * sizePerRow else 1
  }

  /**
   * For simplicity we use Decimal to unify operations for data types whose min/max values can be
   * represented as numbers, e.g. Boolean can be represented as 0 (false) or 1 (true).
   * The two methods below are the contract of conversion.
   */
  def toDecimal(value: Any, dataType: DataType): Decimal = {
    dataType match {
      case _: NumericType | DateType | TimestampType => Decimal(value.toString)
      case BooleanType => if (value.asInstanceOf[Boolean]) Decimal(1) else Decimal(0)
    }
  }

  def fromDecimal(dec: Decimal, dataType: DataType): Any = {
    dataType match {
      case BooleanType => dec.toLong == 1
      case DateType => dec.toInt
      case TimestampType => dec.toLong
      case ByteType => dec.toByte
      case ShortType => dec.toShort
      case IntegerType => dec.toInt
      case LongType => dec.toLong
      case FloatType => dec.toFloat
      case DoubleType => dec.toDouble
      case _: DecimalType => dec
    }
  }

  /**
   * Returns overlapped ranges between two histograms, in the given value range [newMin, newMax].
   */
  def getOverlappedRanges(
      leftHistogram: Histogram,
      rightHistogram: Histogram,
      newMin: Double,
      newMax: Double): Seq[OverlappedRange] = {
    val overlappedRanges = new ArrayBuffer[OverlappedRange]()
    // Only bins whose range intersect [newMin, newMax] have join possibility.
    val leftBins = leftHistogram.bins
      .filter(b => b.lo <= newMax && b.hi >= newMin)
    val rightBins = rightHistogram.bins
      .filter(b => b.lo <= newMax && b.hi >= newMin)

    leftBins.foreach { lb =>
      rightBins.foreach { rb =>
        val (left, leftHeight) = trimBin(lb, leftHistogram.height, newMin, newMax)
        val (right, rightHeight) = trimBin(rb, rightHistogram.height, newMin, newMax)
        // Only collect overlapped ranges.
        if (left.lo <= right.hi && left.hi >= right.lo) {
          // Collect overlapped ranges.
          val range = if (left.lo == left.hi) {
            // Case1: the left bin has only one value
            OverlappedRange(
              lo = left.lo,
              hi = left.lo,
              leftNdv = 1,
              rightNdv = 1,
              leftNumRows = leftHeight,
              rightNumRows = rightHeight / right.ndv
            )
          } else if (right.lo == right.hi) {
            // Case2: the right bin has only one value
            OverlappedRange(
              lo = right.lo,
              hi = right.lo,
              leftNdv = 1,
              rightNdv = 1,
              leftNumRows = leftHeight / left.ndv,
              rightNumRows = rightHeight
            )
          } else if (right.lo >= left.lo && right.hi >= left.hi) {
            // Case3: the left bin is "smaller" than the right bin
            //      left.lo            right.lo     left.hi          right.hi
            // --------+------------------+------------+----------------+------->
            val leftRatio = (left.hi - right.lo) / (left.hi - left.lo)
            val rightRatio = (left.hi - right.lo) / (right.hi - right.lo)
            if (leftRatio == 0) {
              // The overlapped range has only one value.
              OverlappedRange(
                lo = right.lo,
                hi = right.lo,
                leftNdv = 1,
                rightNdv = 1,
                leftNumRows = leftHeight / left.ndv,
                rightNumRows = rightHeight / right.ndv
              )
            } else {
              OverlappedRange(
                lo = right.lo,
                hi = left.hi,
                leftNdv = left.ndv * leftRatio,
                rightNdv = right.ndv * rightRatio,
                leftNumRows = leftHeight * leftRatio,
                rightNumRows = rightHeight * rightRatio
              )
            }
          } else if (right.lo <= left.lo && right.hi <= left.hi) {
            // Case4: the left bin is "larger" than the right bin
            //      right.lo           left.lo      right.hi         left.hi
            // --------+------------------+------------+----------------+------->
            val leftRatio = (right.hi - left.lo) / (left.hi - left.lo)
            val rightRatio = (right.hi - left.lo) / (right.hi - right.lo)
            if (leftRatio == 0) {
              // The overlapped range has only one value.
              OverlappedRange(
                lo = right.hi,
                hi = right.hi,
                leftNdv = 1,
                rightNdv = 1,
                leftNumRows = leftHeight / left.ndv,
                rightNumRows = rightHeight / right.ndv
              )
            } else {
              OverlappedRange(
                lo = left.lo,
                hi = right.hi,
                leftNdv = left.ndv * leftRatio,
                rightNdv = right.ndv * rightRatio,
                leftNumRows = leftHeight * leftRatio,
                rightNumRows = rightHeight * rightRatio
              )
            }
          } else if (right.lo >= left.lo && right.hi <= left.hi) {
            // Case5: the left bin contains the right bin
            //      left.lo            right.lo     right.hi         left.hi
            // --------+------------------+------------+----------------+------->
            val leftRatio = (right.hi - right.lo) / (left.hi - left.lo)
            OverlappedRange(
              lo = right.lo,
              hi = right.hi,
              leftNdv = left.ndv * leftRatio,
              rightNdv = right.ndv,
              leftNumRows = leftHeight * leftRatio,
              rightNumRows = rightHeight
            )
          } else {
            assert(right.lo <= left.lo && right.hi >= left.hi)
            // Case6: the right bin contains the left bin
            //      right.lo           left.lo      left.hi          right.hi
            // --------+------------------+------------+----------------+------->
            val rightRatio = (left.hi - left.lo) / (right.hi - right.lo)
            OverlappedRange(
              lo = left.lo,
              hi = left.hi,
              leftNdv = left.ndv,
              rightNdv = right.ndv * rightRatio,
              leftNumRows = leftHeight,
              rightNumRows = rightHeight * rightRatio
            )
          }
          overlappedRanges += range
        }
      }
    }
    overlappedRanges
  }

  /**
   * Given an original bin and a value range [min, max], returns the trimmed bin and its number of
   * rows.
   */
  def trimBin(bin: HistogramBin, height: Double, min: Double, max: Double)
    : (HistogramBin, Double) = {
    val (lo, hi) = if (bin.lo <= min && bin.hi >= max) {
      //       bin.lo              min          max          bin.hi
      // --------+------------------+------------+-------------+------->
      (min, max)
    } else if (bin.lo <= min && bin.hi >= min) {
      //       bin.lo              min        bin.hi
      // --------+------------------+-----------+------->
      (min, bin.hi)
    } else if (bin.lo <= max && bin.hi >= max) {
      //        bin.lo             max        bin.hi
      // --------+------------------+-----------+------->
      (bin.lo, max)
    } else {
      (bin.lo, bin.hi)
    }

    if (bin.hi == bin.lo) {
      (bin, height)
    } else if (hi == lo) {
      (HistogramBin(lo, hi, 1), height / bin.ndv)
    } else {
      val ratio = (hi - lo) / (bin.hi - bin.lo)
      (HistogramBin(lo, hi, math.ceil(bin.ndv * ratio).toLong), height * ratio)
    }
  }

  /**
    * Returns the number of the first bin into which a column value falls for a specified
    * numeric equi-height histogram.
    *
    * @param value a literal value of a column
    * @param bins an array of bins for a given numeric equi-height histogram
    * @return the id of the first bin into which a column value falls.
    */
  def findFirstBinForValue(value: Double, bins: Array[HistogramBin]): Int = {
    var i = 0
    while ((i < bins.length) && (value > bins(i).hi)) {
      i += 1
    }
    i
  }

  /**
    * Returns the number of the last bin into which a column value falls for a specified
    * numeric equi-height histogram.
    *
    * @param value a literal value of a column
    * @param bins an array of bins for a given numeric equi-height histogram
    * @return the id of the last bin into which a column value falls.
    */
  def findLastBinForValue(value: Double, bins: Array[HistogramBin]): Int = {
    var i = bins.length - 1
    while ((i >= 0) && (value < bins(i).lo)) {
      i -= 1
    }
    i
  }

  /**
    * Returns a percentage of a bin holding values for column value in the range of
    * [lowerValue, higherValue]
    *
    * @param higherValue a given upper bound value of a specified column value range
    * @param lowerValue a given lower bound value of a specified column value range
    * @param bin a single histogram bin
    * @return the percentage of a single bin holding values in [lowerValue, higherValue].
    */
  private def getOccupation(
                             higherValue: Double,
                             lowerValue: Double,
                             bin: HistogramBin): Double = {
    assert(bin.lo <= lowerValue && lowerValue <= higherValue && higherValue <= bin.hi)
    if (bin.hi == bin.lo) {
      // the entire bin is covered in the range
      1.0
    } else if (higherValue == lowerValue) {
      // set percentage to 1/NDV
      1.0 / bin.ndv.toDouble
    } else {
      // Use proration since the range falls inside this bin.
      math.min((higherValue - lowerValue) / (bin.hi - bin.lo), 1.0)
    }
  }

  /**
    * Returns the number of bins for column values in [lowerValue, higherValue].
    * This is an overloaded method. The column value distribution is saved in an
    * equi-height histogram.
    *
    * @param higherId id of the high end bin holding the high end value of a column range
    * @param lowerId id of the low end bin holding the low end value of a column range
    * @param higherEnd a given upper bound value of a specified column value range
    * @param lowerEnd a given lower bound value of a specified column value range
    * @param histogram a numeric equi-height histogram
    * @return the number of bins for column values in [lowerEnd, higherEnd].
    */
  def getOccupationBins(
                         higherId: Int,
                         lowerId: Int,
                         higherEnd: Double,
                         lowerEnd: Double,
                         histogram: Histogram): Double = {
    if (lowerId == higherId) {
      val curBin = histogram.bins(lowerId)
      getOccupation(higherEnd, lowerEnd, curBin)
    } else {
      // compute how much lowerEnd/higherEnd occupies its bin
      val lowerCurBin = histogram.bins(lowerId)
      val lowerPart = getOccupation(lowerCurBin.hi, lowerEnd, lowerCurBin)

      val higherCurBin = histogram.bins(higherId)
      val higherPart = getOccupation(higherEnd, higherCurBin.lo, higherCurBin)

      // the total length is lowerPart + higherPart + bins between them
      lowerPart + higherPart + higherId - lowerId - 1
    }
  }

  /**
    * Returns the number of distinct values, ndv, for column values in [lowerEnd, higherEnd].
    * The column value distribution is saved in an equi-height histogram.
    *
    * @param higherId id of the high end bin holding the high end value of a column range
    * @param lowerId id of the low end bin holding the low end value of a column range
    * @param higherEnd a given upper bound value of a specified column value range
    * @param lowerEnd a given lower bound value of a specified column value range
    * @param histogram a numeric equi-height histogram
    * @return the number of distinct values, ndv, for column values in [lowerEnd, higherEnd].
    */
  def getOccupationNdv(
                        higherId: Int,
                        lowerId: Int,
                        higherEnd: Double,
                        lowerEnd: Double,
                        histogram: Histogram)
  : Long = {
    val ndv: Double = if (higherEnd == lowerEnd) {
      1
    } else if (lowerId == higherId) {
      val curBin = histogram.bins(lowerId)
      getOccupation(higherEnd, lowerEnd, curBin) * curBin.ndv
    } else {
      // compute how much the [lowerEnd, higherEnd] range occupies the bins in a histogram.
      // Our computation has 3 parts: the smallest/min bin, the middle bins, the largest/max bin.
      val minCurBin = histogram.bins(lowerId)
      val minPartNdv = getOccupation(minCurBin.hi, lowerEnd, minCurBin) * minCurBin.ndv

      val maxCurBin = histogram.bins(higherId)
      val maxPartNdv = getOccupation(higherEnd, maxCurBin.lo, maxCurBin) * maxCurBin.ndv

      // The total ndv is minPartNdv + maxPartNdv + Ndvs between them.
      // In order to avoid counting same distinct value twice, we check if the upperBound value
      // of next bin is equal to the hi value of the previous bin.  We bump up
      // ndv value only if the hi values of two consecutive bins are different.
      var middleNdv: Long = 0
      for (i <- histogram.bins.indices) {
        val bin = histogram.bins(i)
        if (bin.hi != bin.lo && i >= lowerId + 1 && i <= higherId - 1) {
          middleNdv += bin.ndv
        }
      }
      minPartNdv + maxPartNdv + middleNdv
    }
    math.round(ndv)
  }

  /**
   * A join between two equi-height histograms may produce multiple overlapped ranges.
   * Each overlapped range is produced by a part of one bin in the left histogram and a part of
   * one bin in the right histogram.
   * @param lo lower bound of this overlapped range.
   * @param hi higher bound of this overlapped range.
   * @param leftNdv ndv in the left part.
   * @param rightNdv ndv in the right part.
   * @param leftNumRows number of rows in the left part.
   * @param rightNumRows number of rows in the right part.
   */
  case class OverlappedRange(
      lo: Double,
      hi: Double,
      leftNdv: Double,
      rightNdv: Double,
      leftNumRows: Double,
      rightNumRows: Double)
}
