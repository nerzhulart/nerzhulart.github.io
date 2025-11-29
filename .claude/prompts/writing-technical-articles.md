# Instructions for Writing Technical Articles

These guidelines help write high-quality technical articles based on successful collaboration patterns.

## Core Principles

### 1. Human Voice, Not AI Voice
- **NO emojis** in the article text or code comments - they immediately signal AI authorship
- **NO checkbox lists** with headers followed by walls of code without explanatory prose
- **NO superlatives** like "✨ Amazing!", "🎯 Perfect!", "⭐⭐⭐⭐⭐"
- Write in natural, flowing prose that a human engineer would write
- Use bullet points sparingly - prefer connected paragraphs

### 2. Code Over Commentary
When demonstrating problems or solutions:

- Don't use verbose numbered lists like "1. First benefit 2. Second benefit 3. Third benefit"
- Instead write: "This approach provides immediate benefits. Testing becomes natural because... Refactoring transforms from dangerous to confident because..."

### 3. Simplified Examples with Disclaimer
- Add upfront disclaimer: "The code examples are deliberately simplified to clearly demonstrate the architectural problems. Real production code would have additional complexity."
- Keep examples focused on the core idea, not production-ready implementations
- Omit error handling, lifecycle management, and edge cases unless directly relevant

## Style Guidelines

### Language and Tone
- Write in English even if I discuss in Russian with you
- Use natural, slightly informal English - not perfect grammar, as the author is not a native speaker
- Prefer simple constructions over complex grammatical structures
- It's OK to have minor grammatical imperfections - this adds authenticity
- Write directly and concisely
- Avoid phrases like "You're absolutely right" or excessive validation
- Technical accuracy over emotional validation
- Use active voice: "This creates problems" not "Problems are created by this"
- Professional but not academic - write like explaining to a colleague

### Code Comments
- Use simple markers: `// Good:`, `// Anti-pattern:`, `// OK:`
- No emojis: ❌ `// ✅ Good:`, ✅ `// Good:`
- Keep comments brief and factual
- Don't over-explain obvious things

### Naming and Consistency
- Use domain-specific names that accurately reflect behavior and responsibility
- Keep parameter names consistent across related examples
- When showing evolution, use same entity names for clarity
- Prefer simpler, more direct names

## Article Structure

### Opening
- Start with clear context: what technology/concept you're discussing
- State the core problem or anti-pattern upfront
- Keep opening concise - get to the point quickly

### Body Structure
1. **Show the problem** - concrete code example demonstrating the issue
2. **Problems** - explain issues in flowing prose, not as numbered list with headers
3. **Solution** - show correct approach with code
4. **Benefits** - explained in connected prose, not bullet points
5. **When exceptions apply** - edge cases where rules don't apply
6. **Conclusion** - reinforce core principle, end with thought-provoking question

### Code Examples
- Examples must be technically correct even if simplified
- Mark non-working code explicitly if demonstrating anti-patterns

## Iterative Refinement

When receiving feedback, focus on these aspects:

**"This doesn't feel human"**
- Remove emojis and superlatives
- Replace lists with flowing prose
- Make code comments terser

**"Code is incomplete or incorrect"**
- Fix technical errors immediately
- Add missing dependencies
- Mark non-working code explicitly

**"Too complex"**
- Simplify examples
- Remove production concerns unless relevant
- Focus on one concept per example

**When reordering or restructuring sections**
- After moving sections around, re-read the entire article for logical flow
- Check that transitions between sections still make sense
- Verify that examples and references haven't been broken
- Structure changes can break narrative coherence - always validate end-to-end

## Final Notes

- First draft won't be perfect - iterate based on feedback
- If code is simplified, say so upfront
- Stay focused on one core idea
- Write for peers, not beginners
