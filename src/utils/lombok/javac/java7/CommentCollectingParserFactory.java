package lombok.javac.java7;

import java.lang.reflect.Field;
import java.util.Map;

import lombok.javac.Comment;

import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.parser.Lexer;
import com.sun.tools.javac.parser.Parser;
import com.sun.tools.javac.parser.ParserFactory;
import com.sun.tools.javac.parser.ScannerFactory;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;

public class CommentCollectingParserFactory extends ParserFactory {
	private final Map<JCCompilationUnit, List<Comment>> commentsMap;
	private final Context context;
	
	static Context.Key<ParserFactory> key() {
		return parserFactoryKey;
	}
	
	protected CommentCollectingParserFactory(Context context, Map<JCCompilationUnit, List<Comment>> commentsMap) {
		super(context);
		this.context = context;
		this.commentsMap = commentsMap;
	}
	
	public Parser newParser(CharSequence input, boolean keepDocComments, boolean keepEndPos, boolean keepLineMap) {
		ScannerFactory scannerFactory = ScannerFactory.instance(context);
		Lexer lexer = scannerFactory.newScanner(input, keepDocComments);
		Object x = new CommentCollectingParser(this, lexer, keepDocComments, keepLineMap, commentsMap);
		return (Parser) x;
		// CCP is based on a stub which extends nothing, but at runtime the stub is replaced with either
		//javac6's EndPosParser which extends Parser, or javac7's EndPosParser which implements Parser.
		//Either way this will work out.
	}
	
	public static void setInCompiler(JavaCompiler compiler, Context context, Map<JCCompilationUnit, List<Comment>> commentsMap) {
		context.put(CommentCollectingParserFactory.key(), (ParserFactory)null);
		Field field;
		try {
			field = JavaCompiler.class.getDeclaredField("parserFactory");
			field.setAccessible(true);
			field.set(compiler, new CommentCollectingParserFactory(context, commentsMap));
		} catch (Exception e) {
			throw new IllegalStateException("Could not set comment sensitive parser in the compiler", e);
		}
	}
}