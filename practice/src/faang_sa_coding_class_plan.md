# FAANG Solutions Architect Coding Class Plan

Goal: become interview-ready for FAANG-level Solutions Architect roles with a coding-heavy preparation track.

Main focus for the first 5-6 months:
- Coding patterns: 55%
- Practical backend coding: 20%
- Cloud/system design: 15%
- Behavioral/story practice: 10%

Use this like a tuition class:
- One main topic per week.
- Fixed homework.
- Mandatory revision.
- Saturday timed test.
- Sunday weekly report.
- A problem is only "mastered" when confidence is 4 or 5.

Confidence scale:
- 1: I do not understand it yet.
- 2: I understand only after seeing the solution.
- 3: I can solve with hints.
- 4: I can solve alone, but slowly.
- 5: I can solve and explain clearly under time pressure.

## 6-Month Roadmap

| Month | Focus | Target Outcome |
|---|---|---|
| Month 1 | Arrays, strings, hash maps, two pointers | Build basic pattern recognition and clean coding habit |
| Month 2 | Sliding window, stack, queue, binary search, intervals | Handle common medium problems with structure |
| Month 3 | Linked lists, trees, BFS, DFS | Become comfortable with recursion and traversal |
| Month 4 | Graphs, heap, top-k, practical backend tasks | Connect coding patterns to real systems |
| Month 5 | Basic DP, backtracking, design-style coding | Strengthen medium-level interview coverage |
| Month 6 | Timed mocks, revision, weak-area repair | Convert knowledge into interview performance |

## Weekly Class Rhythm

| Day | Activity |
|---|---|
| Monday | Learn the week's pattern and solve 1 guided problem slowly |
| Tuesday | Solve 1-2 assigned problems |
| Wednesday | Solve 1-2 assigned problems |
| Thursday | Practical backend coding task |
| Friday | Revision only: redo weak problems and update notes |
| Saturday | Timed test: 1 easy warmup + 1 medium problem |
| Sunday | Weekly report card and next-week adjustment |

## Week 1: Arrays And Hash Maps

Theme: learn how hash maps and sets reduce brute force search.

Target skills:
- Identify when lookup/counting is useful.
- Explain brute force before optimizing.
- Use a dictionary/hash map cleanly.
- Track time and space complexity.
- Handle empty input, duplicates, and repeated values.

### Week 1 Assignments

| Priority | Problem | Difficulty | Target Time | Pattern |
|---|---|---:|---:|---|
| Must | Two Sum | Easy | 20 min | Hash map lookup |
| Must | Contains Duplicate | Easy | 15 min | Set membership |
| Must | Valid Anagram | Easy | 20 min | Frequency count |
| Must | Group Anagrams | Medium | 35 min | Hash map grouping |
| Must | Top K Frequent Elements | Medium | 40 min | Frequency count + heap/bucket |
| Stretch | Product of Array Except Self | Medium | 40 min | Prefix/suffix |
| Stretch | Longest Consecutive Sequence | Medium | 45 min | Set expansion |

### Monday

Class topic:
- What is a hash map?
- Why `O(1)` lookup matters.
- How to move from brute force `O(n^2)` to optimized `O(n)`.

Work:
- Solve Two Sum slowly.
- Write brute force first.
- Then write optimized hash map version.

Checklist:
- Can I explain why the complement lookup works?
- Did I avoid using the same index twice?
- Did I state time complexity as `O(n)`?
- Did I state space complexity as `O(n)`?

### Tuesday

Work:
- Contains Duplicate
- Valid Anagram

Focus:
- Use sets for membership.
- Use hash maps or fixed-size counts for character frequencies.
- Practice edge cases.

Edge cases:
- Empty array/string
- One item
- Repeated values
- Different lengths for anagram

### Wednesday

Work:
- Group Anagrams
- Review Two Sum without looking at notes.

Focus:
- Convert each word into a stable key.
- Compare sorted-string key vs frequency-count key.
- Explain the tradeoff.

### Thursday

Practical backend coding task:
- Write a function that receives a list of log lines and returns error counts per service.

Example input:

```text
2027-01-01T10:00:00Z payments ERROR timeout
2027-01-01T10:01:00Z users INFO login
2027-01-01T10:02:00Z payments ERROR declined
2027-01-01T10:03:00Z orders ERROR failed
```

Expected output:

```text
payments: 2
orders: 1
```

Interview explanation practice:
- What happens with malformed log lines?
- Should service names be case-sensitive?
- How would this change for streaming logs?
- How would this scale in production?

### Friday

Revision only:
- Redo Two Sum from scratch.
- Redo Valid Anagram from scratch.
- Review mistakes from the week.
- Update confidence score for each problem.

No new LeetCode unless all must-do problems are complete.

### Saturday

Timed test:
- Easy warmup: Contains Duplicate in 10-15 minutes.
- Medium test: Group Anagrams in 35 minutes.
- Explain solution out loud.
- Write complexity.
- Add 3 test cases.

Score yourself:
- 0: Could not start
- 1: Started but needed solution
- 2: Solved with hints
- 3: Solved alone but buggy or too slow
- 4: Solved alone within time
- 5: Solved, explained clearly, and tested well

### Sunday

Weekly report template:

```text
Week 1 Report

Hours studied:
Problems attempted:
Problems mastered:
Hardest problem:
Main mistake:
Average confidence:
Saturday test score:
Need repeat next week:
Notes for tutor:
```

Send that report back here, and I will adjust Week 2.

## Mistake Notebook

Use this format every time you get stuck:

```text
Problem:
Mistake type:
Where I got stuck:
Correct idea:
How to recognize next time:
Repeat date:
```

Common mistake types:
- Pattern not recognized
- Wrong data structure
- Off-by-one
- Missed edge case
- Complexity misunderstood
- Code bug
- Needed hint too early
- Could not explain clearly

## Interview Explanation Script

Use this for every coding problem:

```text
First, I will clarify the input and output.
The brute force approach is...
The bottleneck is...
I can optimize using...
The algorithm is...
The edge cases are...
The time complexity is...
The space complexity is...
I would test it with...
```

