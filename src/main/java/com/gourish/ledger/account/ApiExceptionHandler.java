package com.gourish.ledger.account;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.gourish.ledger.transaction.IdempotencyConflictException;
import com.gourish.ledger.transaction.UnbalancedTransactionException;
import com.gourish.ledger.transaction.InsufficientFundsException;
import com.gourish.ledger.reconciliation.BankStatementNotFoundException;
import com.gourish.ledger.reconciliation.InvalidCsvException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DuplicateAccountException.class)
    ProblemDetail duplicate(DuplicateAccountException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail notFound(AccountNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(UnbalancedTransactionException.class)
    ProblemDetail unbalanced(UnbalancedTransactionException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail idempotencyConflict(IdempotencyConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ProblemDetail insufficientFunds(InsufficientFundsException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    @ExceptionHandler(InvalidHierarchyException.class)
    ProblemDetail invalidHierarchy(InvalidHierarchyException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    @ExceptionHandler(InvalidCsvException.class)
    ProblemDetail invalidCsv(InvalidCsvException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(BankStatementNotFoundException.class)
    ProblemDetail statementNotFound(BankStatementNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}