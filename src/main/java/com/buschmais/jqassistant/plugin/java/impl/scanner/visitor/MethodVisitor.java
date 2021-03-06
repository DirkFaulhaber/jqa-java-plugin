package com.buschmais.jqassistant.plugin.java.impl.scanner.visitor;

import com.buschmais.jqassistant.plugin.java.api.model.AnnotationValueDescriptor;
import com.buschmais.jqassistant.plugin.java.api.model.FieldDescriptor;
import com.buschmais.jqassistant.plugin.java.api.model.MethodDescriptor;
import com.buschmais.jqassistant.plugin.java.api.model.ParameterDescriptor;
import com.buschmais.jqassistant.plugin.java.api.model.PrimitiveValueDescriptor;
import com.buschmais.jqassistant.plugin.java.api.model.TypeDescriptor;
import com.buschmais.jqassistant.plugin.java.api.scanner.SignatureHelper;
import com.buschmais.jqassistant.plugin.java.api.scanner.TypeCache;
import com.buschmais.jqassistant.plugin.java.api.scanner.TypeCache.CachedType;

import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MethodVisitor extends org.objectweb.asm.MethodVisitor {

    private static final Logger LOGGER = LoggerFactory.getLogger(VisitorHelper.class);

    /**
     * Annotation indicating a synthetic parameter of a method.
     */
    private static final String JAVA_LANG_SYNTHETIC = "java.lang.Synthetic";

    private TypeCache.CachedType containingType;
    private MethodDescriptor methodDescriptor;
    private VisitorHelper visitorHelper;
    private int syntheticParameters = 0;
    private int cyclomaticComplexity = 1;
    private int line;

    protected MethodVisitor(TypeCache.CachedType containingType, MethodDescriptor methodDescriptor, VisitorHelper visitorHelper) {
        super(Opcodes.ASM5);
        this.containingType = containingType;
        this.methodDescriptor = methodDescriptor;
        this.visitorHelper = visitorHelper;
    }

    @Override
    public org.objectweb.asm.AnnotationVisitor visitParameterAnnotation(final int parameter, final String desc, final boolean visible) {
        String annotationType = SignatureHelper.getType(desc);
        if (JAVA_LANG_SYNTHETIC.equals(annotationType)) {
            // Ignore synthetic parameters add the start of the signature, i.e.
            // determine the number of synthetic parameters
            syntheticParameters = Math.max(syntheticParameters, parameter + 1);
            return null;
        }
        ParameterDescriptor parameterDescriptor = visitorHelper.getParameterDescriptor(methodDescriptor, parameter - syntheticParameters);
        if (parameterDescriptor == null) {
            LOGGER.warn("Cannot find parameter with index " + (parameter - syntheticParameters) + " in method signature "
                    + containingType.getTypeDescriptor().getFullQualifiedName() + "#" + methodDescriptor.getSignature());
            return null;
        }
        AnnotationValueDescriptor annotationDescriptor = visitorHelper.addAnnotation(containingType, parameterDescriptor, SignatureHelper.getType(desc));
        return new AnnotationVisitor(containingType, annotationDescriptor, visitorHelper);
    }

    @Override
    public void visitTypeInsn(final int opcode, final String type) {
        visitorHelper.resolveType(SignatureHelper.getObjectType(type), containingType);
    }

    @Override
    public void visitFieldInsn(final int opcode, final String owner, final String name, final String desc) {
        String fieldSignature = SignatureHelper.getFieldSignature(name, desc);
        TypeCache.CachedType targetType = visitorHelper.resolveType(SignatureHelper.getObjectType(owner), containingType);
        FieldDescriptor fieldDescriptor = visitorHelper.getFieldDescriptor(targetType, fieldSignature);
        switch (opcode) {
        case Opcodes.GETFIELD:
        case Opcodes.GETSTATIC:
            visitorHelper.addReads(methodDescriptor, line, fieldDescriptor);
            break;
        case Opcodes.PUTFIELD:
        case Opcodes.PUTSTATIC:
            visitorHelper.addWrites(methodDescriptor, line, fieldDescriptor);
            break;
        }
    }

    @Override
    public void visitMethodInsn(final int opcode, final String owner, final String name, final String desc, boolean itf) {
        String methodSignature = SignatureHelper.getMethodSignature(name, desc);
        TypeCache.CachedType targetType = visitorHelper.resolveType(SignatureHelper.getObjectType(owner), containingType);
        MethodDescriptor invokedMethodDescriptor = visitorHelper.getMethodDescriptor(targetType, methodSignature);
        visitorHelper.addInvokes(methodDescriptor, line, invokedMethodDescriptor);
    }

  @Override public void visitLdcInsn(final Object cst) {

    /*
     *  HACK
     *  
     *  public class TwoUsingLegalEntityDot {

            public static final String sampleSelect = "select * from foo where legal_entity.id = 17";
        
            public void function2() {
        
                function1(sampleSelect);  <=========== these translate to LDC INSN
            }
        }
        
         javap -c /CAL/PSTOOLKIT/dirk-toolkit/AutomatedArchitectureAnalysis/TESTBED/build/classes/main/test/TwoUsingLegalEntityDot.class
         
          Compiled from "TwoUsingLegalEntityDot.java"
          public class test.TwoUsingLegalEntityDot {
            public static final java.lang.String sampleSelect;
          
            public void function2();
              Code:
                 0: aload_0
                 1: ldc           #3                  // String select * from foo where legal_entity.id = 17
                 3: invokevirtual #4                  // Method function1:(Ljava/lang/String;)I
                 6: pop
                 7: return
          }

     */
    String fqn = containingType.getTypeDescriptor().getFullQualifiedName();
    String methodName = methodDescriptor.getName();

    if(cst instanceof String) {
      
      String cstS = (String) cst;
      String someName = "ldc " + methodName + ", line " + line;
      String fieldSignature = SignatureHelper.getFieldSignature(someName, "Ljava/lang/String;");
      FieldDescriptor fieldDescriptor = visitorHelper.getFieldDescriptor(containingType, fieldSignature);
      fieldDescriptor.setName(someName);
      
      PrimitiveValueDescriptor valueDescriptor = visitorHelper.getValueDescriptor(PrimitiveValueDescriptor.class);
      valueDescriptor.setValue(cstS);
      fieldDescriptor.setValue(valueDescriptor);
      
      visitorHelper.addReads(methodDescriptor, line, fieldDescriptor);
    }
    // HACK
    
    if (cst instanceof Type) {

      String type = SignatureHelper.getType((Type) cst);
      visitorHelper.resolveType(type, containingType);
    }
  }

    @Override
    public void visitMultiANewArrayInsn(final String desc, final int dims) {
        visitorHelper.resolveType(SignatureHelper.getType(desc), containingType);
    }

    @Override
    public void visitLocalVariable(final String name, final String desc, final String signature, final Label start, final Label end, final int index) {
        if (signature != null) {
            new SignatureReader(signature).accept(new DependentTypeSignatureVisitor(containingType, visitorHelper));
        }
    }

    @Override
    public org.objectweb.asm.AnnotationVisitor visitAnnotationDefault() {
        return new AnnotationDefaultVisitor(containingType, this.methodDescriptor, visitorHelper);
    }

    @Override
    public void visitTryCatchBlock(final Label start, final Label end, final Label handler, final String type) {
        if (type != null) {
            String fullQualifiedName = SignatureHelper.getObjectType(type);
            visitorHelper.resolveType(fullQualifiedName, containingType);
        }
    }

    @Override
    public AnnotationVisitor visitAnnotation(final String desc, final boolean visible) {
        AnnotationValueDescriptor annotationDescriptor = visitorHelper.addAnnotation(containingType, methodDescriptor, SignatureHelper.getType(desc));
        return new AnnotationVisitor(containingType, annotationDescriptor, visitorHelper);
    }

    @Override
    public void visitLineNumber(int line, Label start) {
        this.line = line;
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        cyclomaticComplexity++;
    }

    @Override
    public void visitEnd() {
        methodDescriptor.setCyclomaticComplexity(cyclomaticComplexity);
    }
}
