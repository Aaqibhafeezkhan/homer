'use client';

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { useQuestions } from '@/lib/api/queries';
import { QuestionList } from '@/components/questions/question-list';

export default function QuestionsPage() {
  const { data } = useQuestions({ status: 'OPEN' });
  const questions = data?.questions ?? [];

  return (
    <Card>
      <CardHeader>
        <CardTitle>Questions</CardTitle>
        <CardDescription>Open questions awaiting your answer</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {questions.length === 0 ? (
          <p className="text-sm text-muted-foreground">No open questions.</p>
        ) : (
          <QuestionList questions={questions} total={data?.total ?? questions.length} />
        )}
      </CardContent>
    </Card>
  );
}
