package www.phases

import utest.*

object PhaseCacheTests extends TestSuite {
  
  // Test helper types
  type TestId = String
  case class TestValue(data: String, computeCount: Int = 0)
  
  def tests = Tests {
    test("PhaseCache basic functionality") {
      test("should cache computed values") {
        val cache = new PhaseCache[TestId, TestValue]()
        var computeCount = 0
        
        def compute(): PhaseRes[TestId, TestValue] = {
          computeCount += 1
          PhaseRes.Ok(TestValue(s"computed-$computeCount", computeCount))
        }
        
        val key = ("testId", false)
        
        // First call should compute
        val result1 = cache.getOrElse(key)(compute)
        result1 match {
          case PhaseRes.Ok(value) => 
            assert(value.data == "computed-1")
            assert(value.computeCount == 1)
          case _ => assert(false)
        }
        assert(computeCount == 1)
        
        // Second call should return cached value
        val result2 = cache.getOrElse(key)(compute)
        result2 match {
          case PhaseRes.Ok(value) => 
            assert(value.data == "computed-1")
            assert(value.computeCount == 1)
          case _ => assert(false)
        }
        assert(computeCount == 1) // Should not have computed again
      }
      
      test("should handle different keys separately") {
        val cache = new PhaseCache[TestId, TestValue]()
        var computeCount = 0
        
        def compute(): PhaseRes[TestId, TestValue] = {
          computeCount += 1
          PhaseRes.Ok(TestValue(s"computed-$computeCount", computeCount))
        }
        
        val key1 = ("testId1", false)
        val key2 = ("testId2", false)
        val key3 = ("testId1", true) // Same id but different circular flag
        
        val result1 = cache.getOrElse(key1)(compute)
        val result2 = cache.getOrElse(key2)(compute)
        val result3 = cache.getOrElse(key3)(compute)
        
        assert(computeCount == 3) // Should have computed 3 times
        
        // Verify each result is different
        (result1, result2, result3) match {
          case (PhaseRes.Ok(v1), PhaseRes.Ok(v2), PhaseRes.Ok(v3)) =>
            assert(v1.computeCount == 1)
            assert(v2.computeCount == 2)
            assert(v3.computeCount == 3)
          case _ => assert(false)
        }
      }
      
      test("should cache different result types") {
        val cache = new PhaseCache[TestId, TestValue]()
        var computeCount = 0
        
        def computeOk(): PhaseRes[TestId, TestValue] = {
          computeCount += 1
          PhaseRes.Ok(TestValue(s"ok-$computeCount", computeCount))
        }
        
        def computeFailure(): PhaseRes[TestId, TestValue] = {
          computeCount += 1
          PhaseRes.Failure(Map("error" -> Right(s"failure-$computeCount")))
        }
        
        def computeIgnore(): PhaseRes[TestId, TestValue] = {
          computeCount += 1
          PhaseRes.Ignore()
        }
        
        val keyOk = ("ok", false)
        val keyFailure = ("failure", false)
        val keyIgnore = ("ignore", false)
        
        val resultOk = cache.getOrElse(keyOk)(computeOk)
        val resultFailure = cache.getOrElse(keyFailure)(computeFailure)
        val resultIgnore = cache.getOrElse(keyIgnore)(computeIgnore)
        
        // Verify caching works for all result types
        cache.getOrElse(keyOk)(computeOk)
        cache.getOrElse(keyFailure)(computeFailure)
        cache.getOrElse(keyIgnore)(computeIgnore)
        
        assert(computeCount == 3) // Should not have computed again
        
        resultOk match {
          case PhaseRes.Ok(value) => assert(value.data == "ok-1")
          case _ => assert(false)
        }
        
        resultFailure match {
          case PhaseRes.Failure(errors) => assert(errors.contains("error"))
          case _ => assert(false)
        }
        
        resultIgnore match {
          case PhaseRes.Ignore() => assert(true)
          case _ => assert(false)
        }
      }
      
      test("should handle circular dependency flag") {
        val cache = new PhaseCache[TestId, TestValue]()
        var computeCount = 0
        
        def compute(): PhaseRes[TestId, TestValue] = {
          computeCount += 1
          PhaseRes.Ok(TestValue(s"computed-$computeCount", computeCount))
        }
        
        val keyNonCircular = ("testId", false)
        val keyCircular = ("testId", true)
        
        val result1 = cache.getOrElse(keyNonCircular)(compute)
        val result2 = cache.getOrElse(keyCircular)(compute)
        
        assert(computeCount == 2) // Should compute twice for different circular flags
        
        // Verify caching works for both
        cache.getOrElse(keyNonCircular)(compute)
        cache.getOrElse(keyCircular)(compute)
        
        assert(computeCount == 2) // Should not compute again
      }
    }
    
    test("PhaseCache with custom initial capacity") {
      test("should accept custom initial capacity") {
        val cache = new PhaseCache[TestId, TestValue](initialCapacity = 500)
        var computeCount = 0
        
        def compute(): PhaseRes[TestId, TestValue] = {
          computeCount += 1
          PhaseRes.Ok(TestValue(s"computed-$computeCount", computeCount))
        }
        
        val key = ("testId", false)
        val result = cache.getOrElse(key)(compute)
        
        result match {
          case PhaseRes.Ok(value) => assert(value.data == "computed-1")
          case _ => assert(false)
        }
        assert(computeCount == 1)
      }
    }
    
    test("PhaseCache soft reference behavior") {
      test("should handle soft reference collection gracefully") {
        val cache = new PhaseCache[TestId, TestValue]()
        var computeCount = 0
        
        def compute(): PhaseRes[TestId, TestValue] = {
          computeCount += 1
          PhaseRes.Ok(TestValue(s"computed-$computeCount", computeCount))
        }
        
        val key = ("testId", false)
        
        // First computation
        val result1 = cache.getOrElse(key)(compute)
        assert(computeCount == 1)
        
        // Force garbage collection to potentially clear soft references
        System.gc()
        Thread.sleep(10) // Give GC a moment
        
        // This might recompute if soft reference was cleared, or return cached value
        val result2 = cache.getOrElse(key)(compute)
        
        // We can't guarantee the soft reference behavior, but the cache should still work
        result2 match {
          case PhaseRes.Ok(value) => 
            assert(value.data.startsWith("computed-"))
            assert(value.computeCount >= 1)
          case _ => assert(false)
        }
      }
    }
  }
}
