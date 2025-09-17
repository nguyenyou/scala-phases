package www.phases

import www.logging.Logger
import utest.*
import scala.collection.immutable.SortedMap

object PhaseResTests extends TestSuite {
  
  // Test helper types
  type TestId = String
  case class TestValue(data: String)
  
  // Mock logger for testing
  val mockLogger: Logger[Unit] = Logger.DevNull
  
  def tests = Tests {
    test("PhaseRes.Ok") {
      test("should create Ok with value") {
        val value = TestValue("test")
        val result = PhaseRes.Ok[TestId, TestValue](value)
        assert(result.isInstanceOf[PhaseRes.Ok[TestId, TestValue]])
        result match {
          case PhaseRes.Ok(v) => assert(v == value)
          case _ => assert(false)
        }
      }
      
      test("map should transform value") {
        val original = PhaseRes.Ok[TestId, TestValue](TestValue("test"))
        val mapped = original.map(_.data.toUpperCase)
        mapped match {
          case PhaseRes.Ok(value) => assert(value == "TEST")
          case _ => assert(false)
        }
      }
      
      test("flatMap should chain computations") {
        val original = PhaseRes.Ok[TestId, TestValue](TestValue("test"))
        val chained = original.flatMap(v => PhaseRes.Ok[TestId, String](v.data.toUpperCase))
        chained match {
          case PhaseRes.Ok(value) => assert(value == "TEST")
          case _ => assert(false)
        }
      }
      
      test("flatMap should propagate failures") {
        val original = PhaseRes.Ok[TestId, TestValue](TestValue("test"))
        val errors = Map[TestId, Either[Throwable, String]]("id1" -> Right("error"))
        val chained = original.flatMap(_ => PhaseRes.Failure[TestId, String](errors))
        chained match {
          case PhaseRes.Failure(errs) => assert(errs == errors)
          case _ => assert(false)
        }
      }
      
      test("foreach should execute side effect") {
        var executed = false
        val original = PhaseRes.Ok[TestId, TestValue](TestValue("test"))
        val result = original.foreach(_ => executed = true)
        assert(executed)
        result match {
          case PhaseRes.Ok(()) => assert(true)
          case _ => assert(false)
        }
      }
    }
    
    test("PhaseRes.Ignore") {
      test("should create Ignore") {
        val result = PhaseRes.Ignore[TestId, TestValue]()
        assert(result.isInstanceOf[PhaseRes.Ignore[TestId, TestValue]])
      }
      
      test("map should preserve Ignore") {
        val original = PhaseRes.Ignore[TestId, TestValue]()
        val mapped = original.map(_.data.toUpperCase)
        mapped match {
          case PhaseRes.Ignore() => assert(true)
          case _ => assert(false)
        }
      }
      
      test("flatMap should preserve Ignore") {
        val original = PhaseRes.Ignore[TestId, TestValue]()
        val chained = original.flatMap(v => PhaseRes.Ok[TestId, String](v.data))
        chained match {
          case PhaseRes.Ignore() => assert(true)
          case _ => assert(false)
        }
      }
      
      test("foreach should not execute side effect") {
        var executed = false
        val original = PhaseRes.Ignore[TestId, TestValue]()
        val result = original.foreach(_ => executed = true)
        assert(!executed)
        result match {
          case PhaseRes.Ignore() => assert(true)
          case _ => assert(false)
        }
      }
    }
    
    test("PhaseRes.Failure") {
      val testErrors = Map[TestId, Either[Throwable, String]](
        "id1" -> Right("error message"),
        "id2" -> Left(new RuntimeException("exception"))
      )
      
      test("should create Failure with errors") {
        val result = PhaseRes.Failure[TestId, TestValue](testErrors)
        assert(result.isInstanceOf[PhaseRes.Failure[TestId, TestValue]])
        result match {
          case PhaseRes.Failure(errors) => assert(errors == testErrors)
          case _ => assert(false)
        }
      }
      
      test("map should preserve Failure") {
        val original = PhaseRes.Failure[TestId, TestValue](testErrors)
        val mapped = original.map(_.data.toUpperCase)
        mapped match {
          case PhaseRes.Failure(errors) => assert(errors == testErrors)
          case _ => assert(false)
        }
      }
      
      test("flatMap should preserve Failure") {
        val original = PhaseRes.Failure[TestId, TestValue](testErrors)
        val chained = original.flatMap(v => PhaseRes.Ok[TestId, String](v.data))
        chained match {
          case PhaseRes.Failure(errors) => assert(errors == testErrors)
          case _ => assert(false)
        }
      }
      
      test("foreach should not execute side effect") {
        var executed = false
        val original = PhaseRes.Failure[TestId, TestValue](testErrors)
        val result = original.foreach(_ => executed = true)
        assert(!executed)
        result match {
          case PhaseRes.Failure(errors) => assert(errors == testErrors)
          case _ => assert(false)
        }
      }
    }
    
    test("PhaseRes.fromEither") {
      test("should create Ok from Right") {
        val either = Right("success")
        val result = PhaseRes.fromEither("testId", either)
        result match {
          case PhaseRes.Ok(value) => assert(value == "success")
          case _ => assert(false)
        }
      }
      
      test("should create Failure from Left") {
        val either = Left("error message")
        val result = PhaseRes.fromEither("testId", either)
        result match {
          case PhaseRes.Failure(errors) => 
            assert(errors.size == 1)
            assert(errors("testId") == Right("error message"))
          case _ => assert(false)
        }
      }
    }
    
    test("PhaseRes.sequenceSet") {
      test("should sequence all Ok values") {
        val set: Set[PhaseRes[TestId, String]] = Set(
          PhaseRes.Ok[TestId, String]("a"),
          PhaseRes.Ok[TestId, String]("b"),
          PhaseRes.Ok[TestId, String]("c")
        )
        val result = PhaseRes.sequenceSet(set)
        result match {
          case PhaseRes.Ok(values) =>
            assert(values == Set("a", "b", "c"))
          case _ => assert(false)
        }
      }
      
      test("should ignore Ignore values") {
        val set: Set[PhaseRes[TestId, String]] = Set(
          PhaseRes.Ok[TestId, String]("a"),
          PhaseRes.Ignore[TestId, String](),
          PhaseRes.Ok[TestId, String]("b")
        )
        val result = PhaseRes.sequenceSet(set)
        result match {
          case PhaseRes.Ok(values) =>
            assert(values == Set("a", "b"))
          case _ => assert(false)
        }
      }
      
      test("should propagate first failure") {
        val errors1 = Map[TestId, Either[Throwable, String]]("id1" -> Right("error1"))
        val errors2 = Map[TestId, Either[Throwable, String]]("id2" -> Right("error2"))
        val set: Set[PhaseRes[TestId, String]] = Set(
          PhaseRes.Ok[TestId, String]("a"),
          PhaseRes.Failure[TestId, String](errors1),
          PhaseRes.Failure[TestId, String](errors2)
        )
        val result = PhaseRes.sequenceSet(set)
        result match {
          case PhaseRes.Failure(errors) =>
            assert(errors.contains("id1"))
            assert(errors.contains("id2"))
          case _ => assert(false)
        }
      }
      
      test("should handle empty set") {
        val set = Set.empty[PhaseRes[TestId, String]]
        val result = PhaseRes.sequenceSet(set)
        result match {
          case PhaseRes.Ok(values) => assert(values.isEmpty)
          case _ => assert(false)
        }
      }
    }

    test("PhaseRes.sequenceMap") {
      implicit val ordering: Ordering[TestId] = Ordering.String

      test("should sequence all Ok values") {
        val map = SortedMap(
          "id1" -> PhaseRes.Ok[TestId, String]("a"),
          "id2" -> PhaseRes.Ok[TestId, String]("b"),
          "id3" -> PhaseRes.Ok[TestId, String]("c")
        )
        val result = PhaseRes.sequenceMap(map)
        result match {
          case PhaseRes.Ok(values) =>
            assert(values == SortedMap("id1" -> "a", "id2" -> "b", "id3" -> "c"))
          case _ => assert(false)
        }
      }

      test("should ignore Ignore values") {
        val map = SortedMap(
          "id1" -> PhaseRes.Ok[TestId, String]("a"),
          "id2" -> PhaseRes.Ignore[TestId, String](),
          "id3" -> PhaseRes.Ok[TestId, String]("b")
        )
        val result = PhaseRes.sequenceMap(map)
        result match {
          case PhaseRes.Ok(values) =>
            assert(values == SortedMap("id1" -> "a", "id3" -> "b"))
          case _ => assert(false)
        }
      }

      test("should propagate failures") {
        val errors1 = Map[TestId, Either[Throwable, String]]("err1" -> Right("error1"))
        val errors2 = Map[TestId, Either[Throwable, String]]("err2" -> Right("error2"))
        val map = SortedMap(
          "id1" -> PhaseRes.Ok[TestId, String]("a"),
          "id2" -> PhaseRes.Failure[TestId, String](errors1),
          "id3" -> PhaseRes.Failure[TestId, String](errors2)
        )
        val result = PhaseRes.sequenceMap(map)
        result match {
          case PhaseRes.Failure(errors) =>
            assert(errors.contains("err1"))
            assert(errors.contains("err2"))
          case _ => assert(false)
        }
      }

      test("should handle empty map") {
        val map = SortedMap.empty[TestId, PhaseRes[TestId, String]]
        val result = PhaseRes.sequenceMap(map)
        result match {
          case PhaseRes.Ok(values) => assert(values.isEmpty)
          case _ => assert(false)
        }
      }

      test("should handle mixed Ignore and Ok starting with Ignore") {
        val map = SortedMap(
          "id1" -> PhaseRes.Ignore[TestId, String](),
          "id2" -> PhaseRes.Ok[TestId, String]("a")
        )
        val result = PhaseRes.sequenceMap(map)
        result match {
          case PhaseRes.Ok(values) =>
            assert(values == SortedMap("id2" -> "a"))
          case _ => assert(false)
        }
      }
    }

    test("PhaseRes.attempt") {
      test("should return Ok for successful computation") {
        val result = PhaseRes.attempt("testId", mockLogger, PhaseRes.Ok[TestId, String]("success"))
        result match {
          case PhaseRes.Ok(value) => assert(value == "success")
          case _ => assert(false)
        }
      }

      test("should return Failure for failed computation") {
        val errors = Map[TestId, Either[Throwable, String]]("id1" -> Right("error"))
        val result = PhaseRes.attempt("testId", mockLogger, PhaseRes.Failure[TestId, String](errors))
        result match {
          case PhaseRes.Failure(errs) => assert(errs == errors)
          case _ => assert(false)
        }
      }

      test("should catch RuntimeException and convert to Failure") {
        val exception = new RuntimeException("test exception")
        val result = PhaseRes.attempt("testId", mockLogger, throw exception)
        result match {
          case PhaseRes.Failure(errors) =>
            assert(errors.size == 1)
            assert(errors("testId").isLeft)
            errors("testId") match {
              case Left(th) => assert(th.getMessage == "test exception")
              case _ => assert(false)
            }
          case _ => assert(false)
        }
      }

      test("should catch StackOverflowError and convert to Failure") {
        val error = new StackOverflowError("stack overflow")
        val result = PhaseRes.attempt("testId", mockLogger, throw error)
        result match {
          case PhaseRes.Failure(errors) =>
            assert(errors.size == 1)
            assert(errors("testId").isLeft)
            errors("testId") match {
              case Left(th) => assert(th.isInstanceOf[StackOverflowError])
              case _ => assert(false)
            }
          case _ => assert(false)
        }
      }

      test("should re-throw InterruptedException") {
        val exception = new InterruptedException("interrupted")
        try {
          PhaseRes.attempt("testId", mockLogger, throw exception)
          assert(false)
        } catch {
          case _: InterruptedException => assert(true)
          case _ => assert(false)
        }
      }
    }
  }
}