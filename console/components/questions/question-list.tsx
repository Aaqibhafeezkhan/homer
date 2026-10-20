import type { Question } from '@/types/homer';
import { QuestionItem } from './question-item';

/**
 * One page of questions, and a note when the paged list holds more than the page (`total` counts
 * every visible question matching the filters).
 */
export function QuestionList({ questions, total }: { questions: Question[]; total: number }) {
  return (
    <>
      {questions.map((question) => (
        <QuestionItem key={question.id} question={question} />
      ))}
      {total > questions.length && (
        <p className="text-sm text-muted-foreground">
          Showing the oldest {questions.length} of {total} questions; answering them brings the rest into view.
        </p>
      )}
    </>
  );
}
