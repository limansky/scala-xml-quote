package scala.xml.quote
package internal

import scala.xml._
import scala.xml.parsing._

trait QuoteHandler extends ConstructingHandler {
  def unparsed(pos: Int, data: String): Unparsed = Unparsed(data)

  def group(pos: Int, elems: NodeSeq): Group = Group(elems)
}

final class QuoteParser(val input: io.Source, val preserveWS: Boolean)
    extends QuoteHandler
    with MarkupParser
    with ExternalSources {

  def handle: QuoteHandler = this

  override def element1(pscope: NamespaceBinding): NodeSeq = {
    val pos = this.pos
    val (qname, (aMap, scope)) = xTag(pscope)
    val (pre, local) = Utility.prefix(qname) match {
      case Some(p) => (p, qname drop p.length + 1)
      case _       => (null, qname)
    }
    val ts = {
      if (ch == '/') { // empty element
        xToken("/>")
        handle.elemStart(pos, pre, local, aMap, scope)
        NodeSeq.Empty
      } else { // element with content
        xToken('>')
        if (qname == "xml:unparsed")
          return xUnparsed

        handle.elemStart(pos, pre, local, aMap, scope)
        val tmp = content(scope)
        xEndTag(qname)
        tmp
      }
    }

    if (qname == "xml:group")
      handle.group(pos, ts)
    else {
      val res = handle.elem(pos, pre, local, aMap, scope, ts == NodeSeq.Empty, ts)
      handle.elemEnd(pos, pre, local)
      res
    }
  }

  override def content(pscope: NamespaceBinding): NodeSeq = {
    val ts = new NodeBuffer
    var exit = eof
    // todo: optimize seq repr.
    def done = NodeSeq.fromSeq(ts.toList)

    while (!exit) {
      tmppos = pos
      exit = eof

      if (eof)
        return done

      ch match {
        case '<' => // another tag
          nextch(); ch match {
            case '/' => exit = true // end tag
            case _   => content1(pscope, ts)
          }

        // postcond: xEmbeddedBlock == false!
        case '&' => // EntityRef or CharRef
          nextch(); ch match {
            case '#' => // CharacterRef
              nextch()
              val theChar = handle.text(tmppos, xCharRef(() => ch, () => nextch()))
              xToken(';')
              ts &+ theChar
            case _ => // EntityRef
              val n = xName
              xToken(';')
              ts &+ handle.entityRef(tmppos, n)
          }
        case _ => // text content
          appendText(tmppos, ts, xText)
      }
    }
    done
  }

  def xUnparsed: NodeSeq =
    xTakeUntil(handle.unparsed, () => pos, "</xml:unparsed>")

  def xText: String = {
    var exit = false
    while (!exit) {
      putChar(ch)
      nextch()

      exit = eof || (ch == '<') || (ch == '&')
    }
    val str = cbuf.toString
    cbuf.length = 0
    str
  }
}
