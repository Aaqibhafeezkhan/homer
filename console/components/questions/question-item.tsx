'use client';

import { useState } from 'react';
// No rehype-raw on purpose: react-markdown's default HTML escaping is the
// sanitization layer for agent-authored question text (ADR-0019).
import Link from 'next/link';
import ReactMarkdown, { type Components } from 'react-markdown';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { useAnswerQuestion, useCancelDispatch } from '@/lib/api/queries';
import { HomerApiError } from '@/lib/api/client';
import { formatTimestamp } from '@/lib/utils/format';
import { cn } from '@/lib/utils/cn';
import type { Question, QuestionStatus } from '@/types/homer';

const statusStyles: Record<QuestionStatus, { label: string; className: string }> = {
  OPEN: { label: 'Waiting for answer', className: 'bg-amber-500 text-white animate-pulse' },
  ANSWERED: { label: 'Answered', className: 'bg-green-500 text-white' },
  EXPIRED: { label: 'Expired', className: 'bg-gray-500 text-white' },
};

// Minimal markdown styling — the project has no typography plugin, so
// preflight strips list/heading styles; restore the essentials.
const markdownClasses =
  'text-sm space-y-2 [&_ul]:list-disc [&_ul]:pl-5 [&_ol]:list-decimal [&_ol]:pl-5 ' +
  '[&_h1]:text-lg [&_h1]:font-semibold [&_h2]:text-base [&_h2]:font-semibold [&_h3]:font-semibold ' +
  '[&_code]:font-mono [&_code]:bg-muted [&_code]:px-1 [&_code]:rounded ' +
  '[&_pre]:bg-muted [&_pre]:p-2 [&_pre]:rounded [&_pre]:overflow-x-auto ' +
  '[&_blockquote]:border-l-2 [&_blockquote]:pl-3 [&_blockquote]:text-muted-foreground ' +
  '[&_a]:underline';

// A markdown image (`![alt](url)`) is not raw HTML, so react-markdown's
// default escaping does not stop it — a rendered <img> would beacon the
// operator's browser to an agent-supplied URL on inbox view. Render the alt
// text instead of the image so no network request is ever made.
const markdownComponents: Components = {
  img: ({ alt }) => (alt ? <span>{alt}</span> : null),
};

// Which agent answering starts, the run it started, or why starting it failed.
function DispatchNotes({ question }: { question: Question }) {
  return (
    <>
      {question.dispatch && question.status === 'OPEN' && (
        <p className="text-sm text-muted-foreground">
          Answering will start <span className="font-medium text-foreground">{question.dispatch.agentName}</span>.
        </p>
      )}
      {question.dispatch?.status === 'DISPATCHED' && question.dispatch.dispatchedProcessId != null && (
        <p className="text-sm text-muted-foreground">
          Started run{' '}
          <Link className="underline" href={`/processes/${question.dispatch.dispatchedProcessId}`}>
            #{question.dispatch.dispatchedProcessId}
          </Link>.
        </p>
      )}
      {question.dispatch?.status === 'FAILED' && (
        <p className="text-sm text-destructive">Dispatch failed: {question.dispatch.error}</p>
      )}
    </>
  );
}

// One button per offered answer; nothing when the question offers none.
function AnswerOptions({
  options,
  busy,
  onPick,
}: {
  options: string[];
  busy: boolean;
  onPick: (option: string) => void;
}) {
  if (options.length === 0) return null;
  return (
    <div className="flex flex-wrap gap-2">
      {options.map((option) => (
        <Button
          key={option}
          variant="outline"
          size="sm"
          disabled={busy}
          onClick={() => onPick(option)}
        >
          {option}
        </Button>
      ))}
    </div>
  );
}

// The answer given, with when it was given if known.
function AnswerNote({ question }: { question: Question }) {
  return (
    <div className="rounded-md bg-muted p-3 text-sm">
      <span className="font-medium">Answer:</span>{' '}
      <span className="whitespace-pre-wrap">{question.answer}</span>
      {question.answeredAt && (
        <span className="ml-2 font-mono text-xs text-muted-foreground">
          {formatTimestamp(question.answeredAt)}
        </span>
      )}
    </div>
  );
}

// The cancel and answer failures, each as its own alert, in that order.
function ActionErrors({ messages }: { messages: (string | null)[] }) {
  return (
    <>
      {messages.map((message, i) =>
        message ? (
          <p key={i} className="text-sm text-destructive" role="alert">
            {message}
          </p>
        ) : null,
      )}
    </>
  );
}

// The inline text for a failed cancel; a 409/404 means the question is already settled.
function cancelErrorText(isError: boolean, err: unknown): string | null {
  if (!isError) return null;
  if (err instanceof HomerApiError && (err.status === 409 || err.status === 404)) {
    return 'This question can no longer be cancelled — it was already answered or cancelled.';
  }
  return err instanceof Error ? err.message : 'Failed to cancel the question.';
}

// The inline text for a failed answer; a 409 means the question is no longer open.
function answerErrorText(isError: boolean, err: unknown): string | null {
  if (!isError) return null;
  if (err instanceof HomerApiError && err.status === 409) {
    return 'This question is no longer open — it was answered elsewhere or expired.';
  }
  return err instanceof Error ? err.message : 'Failed to submit the answer.';
}

// The answer and cancel mutations, the custom-answer draft and the cancel dialog's state.
function useQuestionActions(question: Question) {
  const answerQuestion = useAnswerQuestion();
  const [customAnswer, setCustomAnswer] = useState('');
  const cancelDispatch = useCancelDispatch();
  const [cancelDialogOpen, setCancelDialogOpen] = useState(false);

  const confirmCancel = async () => {
    if (!question.dispatch) return;
    try {
      await cancelDispatch.mutateAsync(question.dispatch.id);
    } catch {
      // surfaced inline via cancelErrorMessage below
    } finally {
      setCancelDialogOpen(false);
    }
  };

  const submit = async (answer: string) => {
    const trimmed = answer.trim();
    if (!trimmed) return;
    try {
      await answerQuestion.mutateAsync({ id: question.id, answer: trimmed });
    } catch {
      // surfaced inline via answerQuestion.isError below
    }
  };

  return {
    busy: answerQuestion.isPending || cancelDispatch.isPending,
    cancelPending: cancelDispatch.isPending,
    customAnswer,
    setCustomAnswer,
    cancelDialogOpen,
    setCancelDialogOpen,
    confirmCancel,
    submit,
    cancelErrorMessage: cancelErrorText(cancelDispatch.isError, cancelDispatch.error),
    errorMessage: answerErrorText(answerQuestion.isError, answerQuestion.error),
  };
}

type QuestionActions = ReturnType<typeof useQuestionActions>;

// The status badge and when the question was asked.
function QuestionHeader({ question }: { question: Question }) {
  const style = statusStyles[question.status];
  return (
    <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
      <Badge
        variant="outline"
        className={cn('border-transparent', style.className)}
        aria-label={`Question status: ${style.label}`}
      >
        {style.label}
      </Badge>
      <span className="font-mono">{formatTimestamp(question.createdAt)}</span>
    </div>
  );
}

// The free-text answer field and its Send button.
function CustomAnswerForm({ actions }: { actions: QuestionActions }) {
  const { customAnswer, busy } = actions;
  return (
    <form
      className="flex gap-2"
      onSubmit={(e) => {
        e.preventDefault();
        actions.submit(customAnswer);
      }}
    >
      <Input
        value={customAnswer}
        onChange={(e) => actions.setCustomAnswer(e.target.value)}
        placeholder="Type an answer…"
        aria-label="Custom answer"
        disabled={busy}
      />
      <Button
        type="submit"
        size="sm"
        disabled={busy || !customAnswer.trim()}
      >
        Send
      </Button>
    </form>
  );
}

// An open question's controls: the offered answers, the custom answer, cancel, and their failures.
function OpenQuestionControls({ question, actions }: { question: Question; actions: QuestionActions }) {
  return (
    <div className="space-y-3">
      <AnswerOptions options={question.options} busy={actions.busy} onPick={actions.submit} />
      <CustomAnswerForm actions={actions} />
      {question.dispatch && (
        <div className="flex justify-end">
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="text-destructive"
            disabled={actions.busy}
            onClick={() => actions.setCancelDialogOpen(true)}
          >
            Cancel question
          </Button>
        </div>
      )}
      <ActionErrors messages={[actions.cancelErrorMessage, actions.errorMessage]} />
    </div>
  );
}

// Confirms cancelling a dispatching question, naming the agent that will then not start.
function CancelQuestionDialog({ question, actions }: { question: Question; actions: QuestionActions }) {
  return (
    <Dialog open={actions.cancelDialogOpen} onOpenChange={actions.setCancelDialogOpen}>
      <DialogContent aria-describedby={`cancel-question-desc-${question.id}`}>
        <DialogHeader>
          <DialogTitle>Cancel this question?</DialogTitle>
          <DialogDescription id={`cancel-question-desc-${question.id}`}>
            This expires the question and{' '}
            <span className="font-medium text-foreground">{question.dispatch?.agentName}</span>{' '}
            will not start. This cannot be undone.
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button
            variant="outline"
            onClick={() => actions.setCancelDialogOpen(false)}
            disabled={actions.cancelPending}
          >
            Keep waiting
          </Button>
          <Button
            variant="destructive"
            onClick={actions.confirmCancel}
            disabled={actions.cancelPending}
          >
            Yes, cancel
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export function QuestionItem({ question }: { question: Question }) {
  const actions = useQuestionActions(question);
  const expired = question.status === 'EXPIRED';

  return (
    <div
      className={cn('rounded-lg border p-4 space-y-3', expired && 'opacity-60')}
      data-testid={`question-${question.id}`}
    >
      <QuestionHeader question={question} />

      <div className={markdownClasses}>
        <ReactMarkdown components={markdownComponents}>{question.text}</ReactMarkdown>
      </div>

      <DispatchNotes question={question} />

      {question.status === 'ANSWERED' && <AnswerNote question={question} />}

      {expired && (
        <p className="text-sm text-muted-foreground">
          The run ended before this question was answered.
        </p>
      )}

      {question.status === 'OPEN' && <OpenQuestionControls question={question} actions={actions} />}
      <CancelQuestionDialog question={question} actions={actions} />
    </div>
  );
}
