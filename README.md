# scalaz-netty

Some basic usage below:

```scala
import scalaz.netty._

/*
 * A simple server which accepts a connection, echos the incoming
 * data back to the sender, waiting for the client to close the connection.
 */

def log(msg: String): Task[Unit] = ???

val address = new InetSocketAddress("localhost", 9090)

val EchoServer = Netty server address flatMap {
  case (addr, incoming) => {
    incoming flatMap { exchange =>
      for {
        _ <- Process.eval(log(s"accepted connection from $addr"))
        _ <- exchange.read to exchange.write
      } yield ()
    }
  }
}

/*
 * A simple client which sends ByteVector(1, 2, 3) to the server,
 * prints its response and then shuts down.
 */

val DumbClient = Netty client address flatMap { exchange =>
  for {
    _ <- Process(ByteVector(1, 2, 3)) to exchange.write
    data <- exchange.read take 1
    _ <- Process.eval(log(s"received data = $data"))
  } yield ()
}
```

## Requirements

This hard-relies on scalaz-stream 0.5 and will not work on earlier versions, mostly because earlier versions are very buggy in some important ways.

## Future Work

- Thread pools are currently not shared.
- Byte buffers are copied upon receipt.  The only way to *safely* address this problem will be to integrate with Scodec and decode against the directly allocated byte buffers.  Not hard to do, really...
- Frame coding is currently not configurable.  This makes it a lot simpler, but technically also much less flexible.
- Exceptions probably don't propagate properly under all circumstances.
- `Task` thread pool is not configurable (currently uses the default scalaz-concurrent pool for everything)