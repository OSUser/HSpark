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

package org.apache.spark.sql.catalyst.expressions

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.errors.TreeNodeException
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.types.RangeType._
import org.apache.spark.sql.types._
import org.apache.spark.sql.types._

object PartialPredicateOperations {
  // When the checkNull argument of the partialReduce method is false, the partial
  // reduction is nullness-based, i.e., uninterested columns are assigned nulls,
  // which necessitates changes of the null handling from the normal evaluations
  // of predicate expressions. The IsNull/IsNotNull will return indefinite results.
  //
  // When the checkNull argument of the partialReduce method is true, the "is null"
  // and "is not null" will return true or false in a definite manner; while other expressions
  // will evaluate to indefinite values.
  //
  // The whole mechanism is based upon the fact that any expression will evaluate to null
  // if any of its operands is null.
  //
  // There are 3 possible results: TRUE, FALSE, and MAYBE represented by a predicate
  // which will be used to further filter the results
  implicit class partialPredicateReducer(e: Expression) {
    /**
     * @param e the expression to be partially evaluated
     * @param schema the schema of 'e'
     * @return the original attribute for the bound reference
     */
    private def unboundAttributeReference(e: Expression, schema: Seq[Attribute]): Expression = {
      e transform {
        case b: BoundReference => schema(b.ordinal)
      }
    }

    private def unboundBoundReference(br: BoundReference, schema: Seq[Attribute]): Attribute = {
      schema(br.ordinal)
    }

    def notNullReduce(): Expression = {
      val schema = e.references.toSeq
      val input = new GenericInternalRow(schema.size) // an all-null row
      val boundPred = BindReferences.bindReference(e, schema)
      val ars = boundPred.collect {
        case br: BoundReference => br
      }
      if (ars.isEmpty) {
        e
      } else {
        ars.foldLeft(e) {
          (y, x) =>
            val predRefs = y.references.toSeq
            val boundPred = BindReferences.bindReference(y, predRefs)
            val unboundX = unboundBoundReference(x, schema)
            val (b1, e1) = boundPred.partialReduce(input, predRefs, checkNull = true, notNullCheckAttr = Some(unboundX))
            val (b2, e2) = boundPred.partialReduce(input, predRefs, checkNull = false, notNullCheckAttr = Some(unboundX))
            val e2X = if (e2 != null) e2.references.contains(unboundX) else false
            val e1X = if (e1 != null) e1.references.contains(unboundX) else false
            (b1, b2) match {
              case (true, true) => Literal.TrueLiteral
              case (true, false) => IsNull(unboundX)
              case (false, true) => IsNotNull(unboundX)
              case (false, false) => Literal.FalseLiteral
              case (true, null) => Or(IsNull(unboundX), e2)
              case (false, null) => if (e2X) e2
                else And(IsNotNull(unboundX), e2)
              case (null, true) => if (e1X) IsNotNull(unboundX)
                else Or(IsNotNull(unboundX), e1)
              case (null, false) => if (e1X) Literal.FalseLiteral
                else And(IsNull(unboundX), e1)
              case (null, null) => if (e1.fastEquals(e2)) {
                e1
              } else if (e1.fastEquals(y) || e2.fastEquals(y)) {
                y
              } else if (e1X && e2X) {
                e2
              } else if (e1X) {
                And(IsNotNull(unboundX), e2)
              } else if (e2X) {
                Or(And(IsNull(unboundX), e1), e2)
              } else {
                Or(And(IsNull(unboundX), e1), And(IsNotNull(unboundX), e2))
              }
            }
        }
      }
    }

    /**
     *
     * @param input rows to evaluate against
     * @param schema the schema of 'e'
     * @param checkNull the flag to check whether the partial evaluation is
     *                  for nullness checking purpose or not
      * @param notNullCheckAttr the attribute to reduce IsNotNull on
     * @return
     */
    def partialReduce(input: InternalRow, schema: Seq[Attribute], checkNull: Boolean = false,
                      notNullCheckAttr: Option[Attribute] = None):
      (Any, Expression) = {
      e match {
        case And(left, right) =>
          val l = left.partialReduce(input, schema, checkNull, notNullCheckAttr)
          if (l._1 == false) {
            (false, null)
          } else {
            val r = right.partialReduce(input, schema, checkNull, notNullCheckAttr)
            if (r._1 == false) {
              (false, null)
            } else {
              (l._1, r._1) match {
                case (true, true) => (true, null)
                case (true, _) => (null, r._2)
                case (_, true) => (null, l._2)
                case (_, _) =>
                  if ((l._2 fastEquals left) && (r._2 fastEquals right)) {
                    (null, unboundAttributeReference(e, schema))
                  } else {
                    (null, And(l._2, r._2))
                  }
                case _ => sys.error("unexpected child type(s) in partial reduction")
              }
            }
          }
        case Or(left, right) =>
          val l = left.partialReduce(input, schema, checkNull, notNullCheckAttr)
          if (l._1 == true) {
            (true, null)
          } else {
            val r = right.partialReduce(input, schema, checkNull, notNullCheckAttr)
            if (r._1 == true) {
              (true, null)
            } else {
              (l._1, r._1) match {
                case (false, false) => (false, null)
                case (false, _) => (null, r._2)
                case (_, false) => (null, l._2)
                case (_, _) =>
                  if ((l._2 fastEquals left) && (r._2 fastEquals right)) {
                    (null, unboundAttributeReference(e, schema))
                  } else {
                    (null, Or(l._2, r._2))
                  }
                case _ => sys.error("unexpected child type(s) in partial reduction")
              }
            }
          }
        case Not(child) =>
          child.partialReduce(input, schema, checkNull, notNullCheckAttr) match {
            case (b: Boolean, null) => (!b, null)
            case (null, ec: Expression) => if (ec fastEquals child) {
              (null, unboundAttributeReference(e, schema))
            } else {
              (null, Not(ec))
            }
          }
        case In(value, list) =>
          val (evaluatedValue, expr) = value.partialReduce(input, schema, checkNull, notNullCheckAttr)
          if (evaluatedValue == null) {
            val evaluatedList = list.map(e=>e.partialReduce(input, schema, checkNull, notNullCheckAttr) match {
              case (null, e: Expression) => e
              case (d, _)  => Literal.create(d, e.dataType)
            })
            (null, In(expr, evaluatedList))
          } else {
            val evaluatedList: Seq[(Any, Expression)] = list.map(_.partialReduce(input, schema, checkNull, notNullCheckAttr))
            var foundInList = false
            var newList = List[Expression]()
            for (item <- evaluatedList if !foundInList) {
              if (item._1 == null) {
                newList = newList :+ item._2
              } else if (item._2 != null) {
                val cmp = prc2(value.dataType, item._2.dataType, evaluatedValue, item._1)
                if (cmp.isDefined && cmp.get == 0) {
                  foundInList = true
                } else if (cmp.isEmpty || (cmp.isDefined && (cmp.get == 1 || cmp.get == -1))) {
                  newList = newList :+ item._2
                }
              }
            }
            if (foundInList) {
              (true, null)
            } else if (newList.isEmpty) {
              (false, null)
            } else {
              (null, In(expr, newList))
            }
          }
        case InSet(value, hset) =>
          val evaluatedValue = value.partialReduce(input, schema, checkNull, notNullCheckAttr)
          if (evaluatedValue._1 == null) {
            (null, InSet(evaluatedValue._2, hset))
          } else {
            var foundInSet = false
            var newHset = Set[Any]()
            for (item <- hset if !foundInSet) {
              val cmp = prc2(value.dataType, value.dataType, evaluatedValue._1, item)
              if (cmp.isDefined && cmp.get == 0) {
                  foundInSet = true
              } else if (cmp.isEmpty || (cmp.isDefined && (cmp.get == 1 || cmp.get == -1))) {
                newHset = newHset + item
              }
            }
            if (foundInSet) {
              (true, null)
            } else if (newHset.isEmpty) {
              (false, null)
            } else {
              (null, InSet(evaluatedValue._2, newHset))
            }
          }
        case b: BoundReference =>
          val res = input.get(b.ordinal, null)
          (res, schema(b.ordinal))
        case n: NamedExpression =>
          val res = n.eval(input)
          (res, n)
        case IsNull(child) => if (notNullCheckAttr.isDefined) {
            val evalChild = child.partialReduce(input, schema, checkNull, notNullCheckAttr)
            evalChild match {
              case (null, e: Expression) => if (e.references.contains(notNullCheckAttr.get)) (checkNull, null)
                else (null, IsNull(e))
              case (b: Boolean, _) => (b, null)
              case _ => (null, unboundAttributeReference(e, schema))
            }
          } else if (checkNull) {
            val evalChild = child.partialReduce(input, schema, checkNull, notNullCheckAttr)
            if (evalChild._1 == null) {
              (true, null)
            } else {
              (false, null)
            }
          } else {
            (null, unboundAttributeReference(e, schema))
          }
        case IsNotNull(child) => if (notNullCheckAttr.isDefined) {
            val evalChild = child.partialReduce(input, schema, checkNull, notNullCheckAttr)
            evalChild match {
              case (null, e: Expression) => if (e.references.contains(notNullCheckAttr.get)) (!checkNull, null)
                else (null, IsNotNull(e))
              case (b: Boolean, _) => (b, null)
              case _ => (null, unboundAttributeReference(e, schema))
            }
          } else if (checkNull) {
            val evalChild = child.partialReduce(input, schema, checkNull)
            if (evalChild._1 == null) {
              (false, null)
            } else {
              (true, null)
            }
          } else {
            (null, unboundAttributeReference(e, schema))
          }
        // TODO: CAST/Arithmetic could be treated more nicely
        case Cast(_, _, _) => (null, unboundAttributeReference(e, schema))
        // case BinaryArithmetic => null
        case UnaryMinus(_) => (null, unboundAttributeReference(e, schema))
        case EqualTo(left, right) =>
          val evalL = left.partialReduce(input, schema, checkNull, notNullCheckAttr)
          val evalR = right.partialReduce(input, schema, checkNull, notNullCheckAttr)
          if (evalL._1 == null && evalR._1 == null) {
            (null, EqualTo(evalL._2, evalR._2))
          } else if (evalL._1 == null) {
            (null, EqualTo(evalL._2, right))
          } else if (evalR._1 == null) {
            (null, EqualTo(left, evalR._2))
          } else {
            val cmp = prc2(left.dataType, right.dataType, evalL._1, evalR._1)
            if (cmp.isDefined && cmp.get != 1 && cmp.get != -1) {
              (cmp.get == 0, null)
            } else {
              (null, EqualTo(evalL._2, evalR._2))
            }
          }
        case LessThan(left, right) =>
          val evalL = left.partialReduce(input, schema, checkNull, notNullCheckAttr)
          val evalR = right.partialReduce(input, schema, checkNull, notNullCheckAttr)
          if (evalL._1 == null && evalR._1 == null) {
            (null, LessThan(evalL._2, evalR._2))
          } else if (evalL._1 == null) {
            (null, LessThan(evalL._2, right))
          } else if (evalR._1 == null) {
            (null, LessThan(left, evalR._2))
          } else {
            val cmp = prc2(left.dataType, right.dataType, evalL._1, evalR._1)
            if (cmp.isDefined && cmp.get != -1) {
              (cmp.get == -2, null)
            } else {
              (null, LessThan(evalL._2, evalR._2))
            }
          }
        case LessThanOrEqual(left, right) =>
          val evalL = left.partialReduce(input, schema, checkNull, notNullCheckAttr)
          val evalR = right.partialReduce(input, schema, checkNull, notNullCheckAttr)
          if (evalL._1 == null && evalR._1 == null) {
            (null, LessThanOrEqual(evalL._2, evalR._2))
          } else if (evalL._1 == null) {
            (null, LessThanOrEqual(evalL._2, right))
          } else if (evalR._1 == null) {
            (null, LessThanOrEqual(left, evalR._2))
          } else {
            val cmp = prc2(left.dataType, right.dataType, evalL._1, evalR._1)
            if (cmp.isDefined) {
              if (cmp.get == 1) {
                (null, EqualTo(evalL._2, evalR._2))
              } else {
                (cmp.get <= 0, null)
              }
            } else {
              (null, LessThanOrEqual(evalL._2, evalR._2))
            }
          }
        case GreaterThan(left, right) =>
          val evalL = left.partialReduce(input, schema, checkNull, notNullCheckAttr)
          val evalR = right.partialReduce(input, schema, checkNull, notNullCheckAttr)
          if (evalL._1 == null && evalR._1 == null) {
            (null, GreaterThan(evalL._2, evalR._2))
          } else if (evalL._1 == null) {
            (null, GreaterThan(evalL._2, right))
          } else if (evalR._1 == null) {
            (null, GreaterThan(left, evalR._2))
          } else {
            val cmp = prc2(left.dataType, right.dataType, evalL._1, evalR._1)
            if (cmp.isDefined && cmp.get != 1) {
              (cmp.get == 2, null)
            } else {
              (null, GreaterThan(evalL._2, evalR._2))
            }
          }
        case GreaterThanOrEqual(left, right) =>
          val evalL = left.partialReduce(input, schema, checkNull, notNullCheckAttr)
          val evalR = right.partialReduce(input, schema, checkNull, notNullCheckAttr)
          if (evalL._1 == null && evalR._1 == null) {
            (null, GreaterThanOrEqual(evalL._2, evalR._2))
          } else if (evalL._1 == null) {
            (null, GreaterThanOrEqual(evalL._2, right))
          } else if (evalR._1 == null) {
            (null, GreaterThanOrEqual(left, evalR._2))
          } else {
            val cmp = prc2(left.dataType, right.dataType, evalL._1, evalR._1)
            if (cmp.isDefined) {
              if (cmp.get == -1) {
                (null, EqualTo(evalL._2, evalR._2))
              } else {
                (cmp.get >= 0, null)
              }
            } else {
              (null, GreaterThanOrEqual(evalL._2, evalR._2))
            }
          }
        case If(predicate, trueE, falseE) =>
          val (v, _) = predicate.partialReduce(input, schema, checkNull, notNullCheckAttr)
          if (v == null) {
            (null, unboundAttributeReference(e, schema))
          } else if (v.asInstanceOf[Boolean]) {
            trueE.partialReduce(input, schema, checkNull, notNullCheckAttr)
          } else {
            falseE.partialReduce(input, schema, checkNull, notNullCheckAttr)
          }
        case Literal(v, BooleanType) => (v, null)
        case l: LeafExpression =>
          val res = l.eval(input)
          (res, l)
        case _ => (null, unboundAttributeReference(e, schema))
      }
    }

    @inline
    protected def prc2(
                        dataType1: DataType,
                        dataType2: DataType,
                        eval1: Any,
                        eval2: Any): Option[Int] = {
      if (dataType1 != dataType2) {
        throw new TreeNodeException(e, s"Types do not match $dataType1 != $dataType2")
      }

      dataType1 match {
        case nativeType: AtomicType =>
          val pdt: RangeType[nativeType.InternalType] = {
            nativeType.toRangeType[nativeType.InternalType]
          }
          pdt.partialOrdering.tryCompare(
            pdt.toPartiallyOrderingDataType(eval1, nativeType),
            pdt.toPartiallyOrderingDataType(eval2, nativeType))
        case other => sys.error(s"Type $other does not support partially ordered operations")
      }
    }
  }
}

class HBaseInternalRow extends GenericInternalRow {

}
