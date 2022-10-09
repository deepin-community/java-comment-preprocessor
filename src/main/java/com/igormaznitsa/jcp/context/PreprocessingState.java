/* 
 * Copyright 2014 Igor Maznitsa (http://www.igormaznitsa.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.igormaznitsa.jcp.context;

import com.igormaznitsa.jcp.containers.FileInfoContainer;
import com.igormaznitsa.jcp.containers.PreprocessingFlag;
import com.igormaznitsa.jcp.containers.TextFileDataContainer;
import com.igormaznitsa.jcp.exceptions.FilePositionInfo;
import com.igormaznitsa.jcp.exceptions.PreprocessorException;
import com.igormaznitsa.jcp.removers.JavaCommentsRemover;
import com.igormaznitsa.jcp.utils.PreprocessorUtils;
import com.igormaznitsa.jcp.utils.ResetablePrinter;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

/**
 * The class describes a preprocessor state also it contains inside buffers and
 * save data on disk
 *
 * @author Igor Maznitsa (igor.maznitsa@igormaznitsa.com)
 */
public final class PreprocessingState {

  public static final FilePositionInfo[] EMPTY_STACK = new FilePositionInfo[0];

  public static final int MAX_WRITE_BUFFER_SIZE = 65536;
  
  public static class ExcludeIfInfo {

    private final FileInfoContainer fileInfoContainer;
    private final String condition;
    private final int stringIndex;
    
    public ExcludeIfInfo(final FileInfoContainer fileInfoContainer, final String condition, final int stringIndex) {
      this.fileInfoContainer = fileInfoContainer;
      this.condition = condition.trim();
      this.stringIndex = stringIndex;
    }

    public int getStringIndex() {
      return this.stringIndex;
    }

    public FileInfoContainer getFileInfoContainer() {
      return fileInfoContainer;
    }

    public String getCondition() {
      return condition;
    }
  }

  public enum PrinterType {

    NORMAL,
    PREFIX,
    POSTFIX
  }
  private final String globalInCharacterEncoding;
  private final String globalOutCharacterEncoding;
  private final TextFileDataContainer rootReference;
  private final FileInfoContainer rootFileInfo;
  private final LinkedList<TextFileDataContainer> whileStack = new LinkedList<TextFileDataContainer>();
  private final LinkedList<TextFileDataContainer> ifStack = new LinkedList<TextFileDataContainer>();
  private final LinkedList<TextFileDataContainer> includeStack = new LinkedList<TextFileDataContainer>();
  private final LinkedList<ExcludeIfInfo> deferredExcludeStack = new LinkedList<ExcludeIfInfo>();
  private final ResetablePrinter prefixPrinter = new ResetablePrinter(1024);
  private final ResetablePrinter postfixPrinter = new ResetablePrinter(64 * 1024);
  private final ResetablePrinter normalPrinter = new ResetablePrinter(1024);
  private final boolean overrideOnlyIfContentChanged;
  private ResetablePrinter currentPrinter;
  private final EnumSet<PreprocessingFlag> preprocessingFlags = EnumSet.noneOf(PreprocessingFlag.class);
  private TextFileDataContainer activeIf;
  private TextFileDataContainer activeWhile;
  private String lastReadString;
  private final PreprocessorContext context;
  
  PreprocessingState(final PreprocessorContext context, final FileInfoContainer rootFile, final String inEncoding, final String outEncoding, final boolean overrideOnlyIfContentChanged) throws IOException {
    PreprocessorUtils.assertNotNull("The root file is null", rootFile);
    PreprocessorUtils.assertNotNull("InEncoding is null",inEncoding);
    PreprocessorUtils.assertNotNull("OutEncoding is null",outEncoding);

    this.context = context;
    
    this.overrideOnlyIfContentChanged = overrideOnlyIfContentChanged;
    this.globalInCharacterEncoding = inEncoding;
    this.globalOutCharacterEncoding = outEncoding;

    this.rootFileInfo = rootFile;
    init();
    rootReference = openFile(rootFile.getSourceFile());
  }

  PreprocessingState(final PreprocessorContext context, final FileInfoContainer rootFile, final TextFileDataContainer rootContainer, final String inEncoding, final String outEncoding, final boolean overrideOnlyIfContentChanged) {
    PreprocessorUtils.assertNotNull("The root file is null", rootFile);
    PreprocessorUtils.assertNotNull("InEncoding is null", inEncoding);

    this.context = context;
    
    this.globalInCharacterEncoding = inEncoding;
    this.globalOutCharacterEncoding = outEncoding;
    this.overrideOnlyIfContentChanged = overrideOnlyIfContentChanged;
    
    this.rootFileInfo = rootFile;
    init();
    rootReference = rootContainer;
    includeStack.push(rootContainer);
  }

  public String getLastReadString(){
    return this.lastReadString;
  }
  
  public void pushExcludeIfData(final FileInfoContainer infoContainer, final String excludeIfCondition, final int stringIndex) {
    PreprocessorUtils.assertNotNull("File info is null", infoContainer);
    PreprocessorUtils.assertNotNull("Condition is null", excludeIfCondition);

    if (stringIndex < 0) {
      throw new IllegalArgumentException("Unexpected string index ["+stringIndex+']');
    }

    deferredExcludeStack.push(new ExcludeIfInfo(infoContainer, excludeIfCondition, stringIndex));
  }

  public List<ExcludeIfInfo> popAllExcludeIfInfoData() {
    final List<ExcludeIfInfo> result = new ArrayList<ExcludeIfInfo>(deferredExcludeStack);
    deferredExcludeStack.clear();
    return result;
  }

  public ExcludeIfInfo popExcludeIfData() {
    return deferredExcludeStack.pop();
  }

  public Set<PreprocessingFlag> getPreprocessingFlags() {
    return preprocessingFlags;
  }

  public ResetablePrinter getPrinter() throws IOException {
    return currentPrinter;
  }

  public TextFileDataContainer getRootTextContainer() {
    return rootReference;
  }

  public TextFileDataContainer openFile(final File file) throws IOException {
    PreprocessorUtils.assertNotNull("The file is null", file);

    final AtomicBoolean endedByNextLineContainer = new AtomicBoolean();
    
    final String[] texts = PreprocessorUtils.readWholeTextFileIntoArray(file, globalInCharacterEncoding, endedByNextLineContainer);
    final TextFileDataContainer newContainer = new TextFileDataContainer(file, texts, endedByNextLineContainer.get(), 0);
    includeStack.push(newContainer);
    return newContainer;
  }

  public TextFileDataContainer peekFile() {
    return includeStack.peek();
  }

  List<TextFileDataContainer> getCurrentIncludeStack(){
    return this.includeStack;
  }
  
  public FilePositionInfo [] makeIncludeStack(){
    final FilePositionInfo[] stack = new FilePositionInfo[includeStack.size()];
    for (int i = 0; i < includeStack.size(); i++) {
      final TextFileDataContainer fileContainer = includeStack.get(i);
      stack[i] = new FilePositionInfo(fileContainer.getFile(), fileContainer.getLastReadStringIndex());
    }
    return stack;
  }
  
  public TextFileDataContainer popTextContainer() {
    if (includeStack.isEmpty()){
      throw new IllegalStateException("Include stack is empty");
    }
    return includeStack.pop();
  }

  public FileInfoContainer getRootFileInfo() {
    return rootFileInfo;
  }

  public boolean isIncludeStackEmpty(){
    return includeStack.isEmpty();
  }
  
  public boolean isOnlyRootOnStack() {
    return includeStack.size() == 1;
  }

  private TextFileDataContainer cloneTopTextDataContainer(final boolean useLastReadStringIndex) {
    final TextFileDataContainer topElement = includeStack.peek();
    return new TextFileDataContainer(topElement, useLastReadStringIndex ? topElement.getLastReadStringIndex() : topElement.getNextStringIndex());
  }

  public PreprocessingState popWhile() {
    final TextFileDataContainer whileOnTop = whileStack.pop();
    if (whileOnTop == activeWhile) {
      preprocessingFlags.remove(PreprocessingFlag.BREAK_COMMAND);
      if (whileStack.isEmpty()) {
        activeWhile = null;
      }
      else {
        activeWhile = whileStack.peek();
      }
    }
    return this;
  }

  public PreprocessingState pushWhile(final boolean makeActive) {
    final TextFileDataContainer whileRef = cloneTopTextDataContainer(true);
    whileStack.push(whileRef);
    if (makeActive) {
      activeWhile = whileRef;
    }
    return this;
  }

  public TextFileDataContainer peekWhile() {
    return whileStack.peek();
  }

  public boolean hasReadLineNextLineInEnd(){
    return includeStack.peek().isPresentedNextLineOnReadString();
  }
  
  public String nextLine() {
    final String result = includeStack.peek().nextLine();
    this.lastReadString = result;
    return result;
  }

  public PreprocessingState goToString(final int stringIndex) {
    includeStack.peek().setNextStringIndex(stringIndex);
    return this;
  }

  public PreprocessingState pushIf(final boolean makeActive) {
    final TextFileDataContainer ifRef = cloneTopTextDataContainer(true);
    ifStack.push(ifRef);
    if (makeActive) {
      activeIf = ifRef;
    }
    return this;
  }

  public void popAllIFUntilContainerWithFile(final TextFileDataContainer container) {
    final File file = container.getFile();
    final int stringIndex = container.getNextStringIndex();
    while (!ifStack.isEmpty()) {
      final TextFileDataContainer top = ifStack.peek();
      if (!top.getFile().equals(file) || top.getNextStringIndex() <= stringIndex) {
        break;
      }
      else {
        ifStack.pop();
      }
    }
  }

  public PreprocessingState popIf() {
    final TextFileDataContainer ifRef = ifStack.pop();
    if (ifRef == activeIf) {
      if (ifStack.isEmpty()) {
        activeIf = null;
      }
      else {
        activeIf = ifStack.peek();
      }
    }
    return this;
  }

  public boolean isAtActiveWhile() {
    if (whileStack.isEmpty()) {
      return true;
    }
    else {
      return activeWhile == whileStack.peek();
    }
  }

  public boolean isAtActiveIf() {
    if (ifStack.isEmpty()) {
      return true;
    }
    else {
      return ifStack.peek() == activeIf;
    }
  }

  public boolean isDirectiveCanBeProcessedIgnoreBreak() {
    return isAtActiveIf() && isAtActiveWhile() && !preprocessingFlags.contains(PreprocessingFlag.IF_CONDITION_FALSE);
  }

  public boolean isDirectiveCanBeProcessed() {
    return isDirectiveCanBeProcessedIgnoreBreak() && !preprocessingFlags.contains(PreprocessingFlag.BREAK_COMMAND);
  }

  public TextFileDataContainer peekIf() {
    return ifStack.peek();
  }

  public boolean isIfStackEmpty() {
    return ifStack.isEmpty();
  }

  public boolean isWhileStackEmpty() {
    return whileStack.isEmpty();
  }

  private void init() {
    preprocessingFlags.clear();
    resetPrinters();

    setPrinter(PrinterType.NORMAL);
  }

  public void setPrinter(final PrinterType type) {
    PreprocessorUtils.assertNotNull("Type is null", type);
    switch (type) {
      case NORMAL:
        currentPrinter = normalPrinter;
        break;
      case POSTFIX:
        currentPrinter = postfixPrinter;
        break;
      case PREFIX:
        currentPrinter = prefixPrinter;
        break;
      default:
        throw new IllegalArgumentException("Unsupported type detected [" + type.name() + ']');
    }
  }

  public void resetPrinters() {
    normalPrinter.reset();
    prefixPrinter.reset();
    postfixPrinter.reset();

    currentPrinter = normalPrinter;
  }

  public void saveBuffersToStreams(final OutputStream prefix, final OutputStream normal, final OutputStream postfix) throws IOException {
    prefixPrinter.writeBufferTo(new BufferedWriter(new OutputStreamWriter(prefix, globalOutCharacterEncoding)));
    normalPrinter.writeBufferTo(new BufferedWriter(new OutputStreamWriter(prefix, globalOutCharacterEncoding)));
    postfixPrinter.writeBufferTo(new BufferedWriter(new OutputStreamWriter(prefix, globalOutCharacterEncoding)));
  }

  public boolean saveBuffersToFile(final File outFile, final boolean removeComments) throws IOException {
    final File path = outFile.getParentFile();

    if (path != null && !path.exists() && !path.mkdirs()) {
      throw new IOException("Can't make directory [" + PreprocessorUtils.getFilePath(path) + ']');
    }

    Writer writer = null;

    try {
      final int totatBufferedChars = prefixPrinter.getSize() + normalPrinter.getSize() + postfixPrinter.getSize();
      final int BUFFER_SIZE = Math.min(totatBufferedChars << 1, MAX_WRITE_BUFFER_SIZE);
      
      boolean wasSaved = false;
      
      if (this.overrideOnlyIfContentChanged){
        String content = ((StringWriter) writePrinterBuffers(new StringWriter(totatBufferedChars))).toString();
        if (removeComments){
          content = ((StringWriter)new JavaCommentsRemover(new StringReader(content), new StringWriter(totatBufferedChars)).process()).toString();
        }
        
        boolean needWrite = true; // better write than not
        final byte [] contentInBinaryForm = content.getBytes(globalOutCharacterEncoding);
        if (outFile.isFile() && outFile.length() == contentInBinaryForm.length) {
          // If file exists and has the same content, then skip overwriting it
          InputStream currentFileInputStream = null;
          try {
            currentFileInputStream = new BufferedInputStream(new FileInputStream(outFile),Math.max(16384, (int)outFile.length()));
            needWrite = !IOUtils.contentEquals(currentFileInputStream,new ByteArrayInputStream(contentInBinaryForm));
          } finally {
            IOUtils.closeQuietly(currentFileInputStream);
          }
        }
        if (needWrite) {
          FileUtils.writeByteArrayToFile(outFile, contentInBinaryForm, false);
          wasSaved = true;
        } else {
          this.context.logDebug("Ignore writing data for "+outFile+" because its content has not been changed");
        }
      }else{
        if (removeComments) {
          final String joinedBufferContent = ((StringWriter) writePrinterBuffers(new StringWriter(totatBufferedChars))).toString();
          writer = new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(outFile, false), BUFFER_SIZE), globalOutCharacterEncoding);
          new JavaCommentsRemover(new StringReader(joinedBufferContent), writer).process();
          wasSaved = true;
        }
        else {
          writer = new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(outFile, false), BUFFER_SIZE), globalOutCharacterEncoding);
          writePrinterBuffers(writer);
          wasSaved = true;
        }
      }
      return wasSaved;
    }
    finally {
      IOUtils.closeQuietly(writer);
    }
  }

  public Writer writePrinterBuffers(final Writer writer) throws IOException {
    if (!prefixPrinter.isEmpty()) {
      prefixPrinter.writeBufferTo(writer);
    }

    if (!normalPrinter.isEmpty()) {
      normalPrinter.writeBufferTo(writer);
    }

    if (!postfixPrinter.isEmpty()) {
      postfixPrinter.writeBufferTo(writer);
    }
    
    return writer;
  }

  public PreprocessorException makeException(final String message, final String causeString, final Throwable cause) {
    return new PreprocessorException(message, causeString, makeIncludeStack(), cause);
  }

  public void dispose() {
    this.deferredExcludeStack.clear();
    this.ifStack.clear();
    this.includeStack.clear();
    this.whileStack.clear();
  }
}
