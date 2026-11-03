package tz.co.pharmacy.modules.finance.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tz.co.pharmacy.modules.finance.domain.Expense;

public interface ExpenseRepository
    extends JpaRepository<Expense, Long>, JpaSpecificationExecutor<Expense> {}
