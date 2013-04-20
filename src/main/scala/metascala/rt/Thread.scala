package metascala.rt

import scala.collection.mutable
import annotation.tailrec
import collection.mutable.ArrayBuffer
import metascala._
import imm.Attached.LineNumber
import metascala.imm.Attached.LineNumber
import metascala.opcodes.OpCode
import scala.Some
import metascala.UncaughtVmException
import metascala.vrt
import metascala.imm
import metascala.imm.Access

/**
 * A single thread within the Metascala VM.
 */
class Thread(val threadStack: mutable.ArrayStack[Frame] = mutable.ArrayStack())(implicit val vm: VM){
  import vm._

  private[this] var opCount = 0L
  def getOpCount = opCount
  def frame = threadStack.top

  var returnedVal: Any = 0

  def getStackTrace =
    threadStack.map { f =>
      new StackTraceElement(
        f.runningClass.name,
        if (f.method.method.code != imm.Code()) f.method.sig.unparse + " " + f.method.method.code.insns(f.pc) else "",
        f.runningClass.clsData.misc.sourceFile.getOrElse("[no source]"),
        f.method.method.code.attachments.flatten.reverse.collect{
          case LineNumber(line, startPc) if startPc < f.pc => line
        }.headOption.getOrElse(-1)
      )
    }.toList

  def indent = "\t" * threadStack.filter(_.method.sig.name != "Dummy").length

  def swapOpCode(opcode: OpCode) = {
    val insnsList = frame.method.insns
    insnsList(frame.pc-1) = opcode
    opcode.op(this)
  }

  final def step() = {
    val insnsList = frame.method.insns
    val node = insnsList(frame.pc)

//    println(indent + frame.runningClass.name + "/" + frame.method.sig.unparse + ": " + frame.stackDump)
//    println(indent + "---------------------- " + frame.pc + "\t" + node )
//    println(indent + vm.Heap.dump.replace("\n", "\n" + indent))
    frame.pc += 1
    opCount += 1
    node.op(this)

  }

  def trace = {

    threadStack.map( f =>
      new StackTraceElement(
        f.runningClass.name,
        f.method.method.name,
        f.runningClass.clsData.misc.sourceFile.getOrElse("<unknown file>"),
        0
      )
    ).toArray
  }

  def returnVal(x: Int) = {
    val oldTop = threadStack.pop()

    threadStack.headOption match{
      case Some(frame) =>
        val tmp = for (i <- 0 until x) yield oldTop.pop
        for (i <- tmp.reverse) frame.push(i)
      case None =>
        returnedVal = popVirtual(oldTop.method.method.desc.ret, () => oldTop.pop)
    }
  }

  @tailrec final def throwException(ex: vrt.Obj, print: Boolean = true): Unit = {
    threadStack.headOption match{
      case Some(frame)=>
        val handler =
          frame.method.method.misc.tryCatchBlocks
            .filter{x =>
            x.start <= frame.pc &&
              x.end >= frame.pc &&
              !x.blockType.isDefined ||
              ex.cls.typeAncestry.contains(x.blockType.get)
          }.headOption

        handler match{
          case None =>
            threadStack.pop()
            throwException(ex, false)
          case Some(imm.TryCatchBlock(start, end, handler, blockType)) =>
            frame.pc = handler
            frame.popAll
            frame.push(ex.address)
        }
      case None =>
        throw new UncaughtVmException(
          popVirtual(ex.cls.clsData.tpe, () => ex.address).cast[Throwable]
        )
    }
  }
  def reader(src: Seq[Val], index: Int) = {
    var i = index
    () => {
      i += 1
      src(i - 1)
    }
  }
  def writer(src: mutable.Seq[Val], index: Int) = {
    var i = index
    (x: Int) => {
      i += 1
      src(i - 1) = x
    }
  }
  def popVirtual(tpe: imm.Type, src: () => Val, refs: mutable.Map[Int, Any] = mutable.Map.empty): Any = {
    tpe match {
      case imm.Type.Prim('V') => ()
      case imm.Type.Prim(c) => prims(c).read(src())
      case _ => //reference type
        val address = src()
        if(address == 0) null
        else if (refs.contains(address)) refs(address)
        else tpe match{
          case t @ imm.Type.Cls(name) =>
            val obj = vrt.unsafe.allocateInstance(Class.forName(address.obj.cls.name.toDot))
            refs += (address -> obj)
            var index = 0
            for(field <- address.obj.cls.fieldList.distinct){
              // workaround for http://bugs.sun.com/view_bug.do?bug_id=4763881
              if (field.name == "backtrace") index += 1
              else{
                val f = getAllFields(obj.getClass).find(_.getName == field.name).get
                f.setAccessible(true)
                f.set(obj, popVirtual(field.desc, reader(vm.Heap.memory, address + 2 + index), refs))
                index += field.desc.size
              }
            }
            obj
          case t @ imm.Type.Arr(tpe) =>
            val clsObj = forName(tpe.unparse.toDot)
            val newArr = java.lang.reflect.Array.newInstance(clsObj, address.arr.length)

            for(i <- 0 until address.arr.length){

              val cooked = tpe match{
                case imm.Type.Prim(c) => prims(c).read(vm.Heap.memory, address + 2 + i)
                case x => popVirtual(tpe, reader(vm.Heap.memory, address + 2 + i * tpe.size))
              }
              java.lang.reflect.Array.set(newArr, i, cooked)
            }
            newArr
        }
    }
  }

  def pushVirtual(thing: Any): Seq[Int] = {
    val tmp = new mutable.Stack[Int]()
    pushVirtual(thing, tmp.push(_))
    tmp
  }

  def pushVirtual(thing: Any, out: Val => Unit): Unit = {
    thing match {
      case null => out(0)
      case b: Boolean => Z.write(b, out)
      case b: Byte    => B.write(b, out)
      case b: Char    => C.write(b, out)
      case b: Short   => S.write(b, out)
      case b: Int     => I.write(b, out)
      case b: Float   => F.write(b, out)
      case b: Long    => J.write(b, out)
      case b: Double  => D.write(b, out)
      case b: Array[_] =>
        val arr = vrt.Arr.allocate(imm.Type.Arr.read(b.getClass.getName).innerType,
          b.flatMap{x =>
            pushVirtual(x)
          }
        )
        out(arr.address)
      case b: Any =>
        val obj = vrt.Obj.allocate(b.getClass.getName.toSlash)
        var index = 0
        for(field <- obj.cls.clsData.fields.filter(!_.static)){
          val f = b.getClass.getDeclaredField(field.name)
          f.setAccessible(true)

          pushVirtual(f.get(b), {x =>
            vm.Heap(obj.address + 2 + index) = x
            index += 1
          })
        }

        out(obj.address)
    }
  }


  final def prepInvoke(mRef: rt.Method,
                       args: Seq[Int]) = {
    //println(indent + "PrepInvoke " + mRef + " with " + args)


    mRef match{
      case rt.Method.Native(clsName, imm.Sig(name, desc), op) =>
        threadStack.headOption.map(f => args.map(f.push))
        op(this)
      case m @ rt.Method.Cls(cls, methodIndex, method) =>
        assert((m.method.access & Access.Native) == 0, "method cannot be native: " + cls.name + " " + method.name)

        val startFrame = new Frame(
          runningClass = cls,
          method = m,
        locals = mutable.Seq(args:_*).padTo(m.method.misc.maxLocals, 0)
        )

        //log(indent + "locals " + startFrame.locals)
        threadStack.push(startFrame)
    }
  }
  final def prepInvoke(tpe: imm.Type,
                       sig: imm.Sig,
                       args: Seq[Any])
                       : Unit = {

    val tmp = mutable.Buffer.empty[Val]
    for(arg <- args.reverse){
      this.pushVirtual(arg, {v =>
        tmp.append(v)
      })
    }
    prepInvoke(
      vm.resolveDirectRef(tpe.cast[imm.Type.Cls], sig).get,
      tmp.reverse
    )


  }
  def invoke(mRef: rt.Method, args: Seq[Int]): Any = {
    val startHeight = threadStack.length
    prepInvoke(mRef, args)

    while(threadStack.length != startHeight) step()

    returnedVal
  }

  def invoke(cls: imm.Type.Cls, sig: imm.Sig, args: Seq[Any]): Any = {
    val startHeight = threadStack.length
    prepInvoke(cls, sig, args)

    while(threadStack.length != startHeight) step()

    returnedVal
  }
}

case class FrameDump(clsName: String,
                     methodName: String,
                     fileName: String,
                     lineNumber: Int)


/**
 * The stack frame created by every method call
 */
class Frame(var pc: Int = 0,
            val runningClass: rt.Cls,
            val method: rt.Method.Cls,
            val locals: mutable.Seq[Val] = mutable.Seq.empty){

  private[this] val stack = new Array[Int](method.method.misc.maxStack)
  private[this] var index = 0
  def push(n: Int) = {

    stack(index) = n
    index += 1
  }
  def pop = {
    index -= 1
    stack(index)
  }
  def popAll = {
    index = 0
  }
  def stackDump = stack.take(index).toList
}


