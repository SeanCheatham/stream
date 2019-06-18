package com.seancheatham.stream.server.av

import org.scalatest.{Matchers, WordSpec}

class VideoSpec extends WordSpec with Matchers {

  "An external camera source" can {
    "be captured as a Source[Frame, _]" in {
      assert(false)
    }
  }

  "An external RTMP source" can {
    "be captured as a Source[Frame, _]" in {
      assert(false)
    }
  }

  "An external RTMP destination" can {
    "be implemented as a Sink[Frame, _]" in {
      assert(false)
    }
  }

}
