import java.nio.ByteBuffer

import com.google.protobuf.ByteString

def main(args: Array[String]): Unit = {
    val a = ByteString.copyFromUtf8("we")
    val b = ByteString.copyFromUtf8("we")
    println(a.toStringUtf8)
    println(b.toStringUtf8)
  }