package smalltalk.compiler.symbols;

import org.antlr.symtab.MethodSymbol;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.ArrayList;
import java.util.List;

/**
 * A block is an anonymous method defined within a method or another block.
 * Ala gnu impl., blocks aren't stored en masse inline.
 * <p>
 * Blocks are tracked by enclosing method's {@link STCompiledBlock} object.
 * <p>
 * A method does not have a block that holds the locals. We mix the
 * arguments and locals together to be consistent with blocks
 * like [:x | |a b| ...] that have both arguments and locals.
 * <p>
 * Block/method symbols also keep a reference to an {@link STCompiledBlock}
 * object that results from compilation. The VM loads these compiled
 * blocks and creates {@see STMetaClassObject} objects for each class.
 * <p>
 * In some sense, a block should subclass a method symbol instead of
 * method subclassing block. But, method is somehow a "larger" concept
 * than a block and can be seen as a specialization. As far as implementation,
 * however, it would be better to have block subclass the method to
 * add the index field. That is how I originally had it but it got confusing
 * from a usage point of view. For example, look at the name and argument type
 * of this method:
 * <p>
 * STCompiledBlock getCompiledBlock(STMethod blk, ...)
 * <p>
 * Weird, right? Now, STBlock pretty much has all fields and I view a
 * method as a block with a name.
 */
public class STBlock extends MethodSymbol {
    /**
     * The block number within the surrounding method or block.
     * To push a code block onto the operand stack at runtime,
     * we push its index as an argument of the BLOCK instruction.
     * It's also used to conjure up the method name; see constructor.
     */
    public final int index;

    public int numNestedBlocks;

    public STCompiledBlock compiledBlock;

    private List<String> locals = new ArrayList<>();
    private int nargs = 0;
    private int nlocals = 0;

    /**
     * Used by subclass STMethod
     */
    protected STBlock(String name, ParserRuleContext tree) {
        super(name);
        setDefNode(tree);
        index = -1;
    }

    /**
     * Create a block object within a specific method
     */
    public STBlock(STMethod method, ParserRuleContext tree) {
        super(method.getName() + "-block" + method.numNestedBlocks);
        setDefNode(tree);
        index = method.numNestedBlocks++;
    }

    public boolean isMethod() {
        return false;
    }

    public int nargs() {
        return nargs;
    } // fill in

    public void addLocalVariable(String name) {
        doAddLocal(name);
        nlocals++;
    }

    public void addArgument(String name) {
        doAddLocal(name);
        nargs++;
    }

    private void doAddLocal(String name) {
        if (locals.contains(name)) {
            throw new IllegalStateException("There is already an argument or local variable '" + name + "'");
        }
        locals.add(name);
    }

    public int nlocals() {
        return nlocals;
    } // fill in

    /**
     * Given the name of a local variable or argument, return the index from 0.
     * The arguments come first and then the locals. For example,
     * at: x put: y [|a| ...]
     * has  indexes x@0, y@1, a@x.
     */
    public int getLocalIndex(String name) {
        // fill in
        return locals.indexOf(name);
    }

    public int getArgumentIndex(String name) {
        return getLocalIndex(name);
    }
}
